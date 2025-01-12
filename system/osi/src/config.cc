/******************************************************************************
 *
 *  Copyright 2017 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include "osi/include/config.h"

#include <base/files/file_util.h>
#include <bluetooth/log.h>
#include <ctype.h>
#include <fcntl.h>
#include <libgen.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <sstream>
#include <type_traits>

using namespace bluetooth;

void section_t::Set(std::string key, std::string value) {
  for (entry_t& entry : entries) {
    if (entry.key == key) {
      entry.value = value;
      return;
    }
  }
  // add a new key to the section
  entries.emplace_back(
      entry_t{.key = std::move(key), .value = std::move(value)});
}

std::list<entry_t>::iterator section_t::Find(const std::string& key) {
  return std::find_if(
      entries.begin(), entries.end(),
      [&key](const entry_t& entry) { return entry.key == key; });
}

bool section_t::Has(const std::string& key) {
  return Find(key) != entries.end();
}

std::list<section_t>::iterator config_t::Find(const std::string& section) {
  return std::find_if(
      sections.begin(), sections.end(),
      [&section](const section_t& sec) { return sec.name == section; });
}

bool config_t::Has(const std::string& key) {
  return Find(key) != sections.end();
}

static bool config_parse(FILE* fp, config_t* config);

template <typename T,
          class = typename std::enable_if<std::is_same<
              config_t, typename std::remove_const<T>::type>::value>>
static auto section_find(T& config, const std::string& section) {
  return std::find_if(
      config.sections.begin(), config.sections.end(),
      [&section](const section_t& sec) { return sec.name == section; });
}

static const entry_t* entry_find(const config_t& config,
                                 const std::string& section,
                                 const std::string& key) {
  auto sec = section_find(config, section);
  if (sec == config.sections.end()) return nullptr;

  for (const entry_t& entry : sec->entries) {
    if (entry.key == key) return &entry;
  }

  return nullptr;
}

std::unique_ptr<config_t> config_new_empty(void) {
  return std::make_unique<config_t>();
}

std::unique_ptr<config_t> config_new(const char* filename) {
  log::assert_that(filename != nullptr, "assert failed: filename != nullptr");

  std::unique_ptr<config_t> config = config_new_empty();

  FILE* fp = fopen(filename, "rt");
  if (!fp) {
    log::error("unable to open file '{}': {}", filename, strerror(errno));
    return nullptr;
  }

  if (!config_parse(fp, config.get())) {
    config.reset();
  }

  fclose(fp);
  return config;
}

std::string checksum_read(const char* filename) {
  base::FilePath path(filename);
  if (!base::PathExists(path)) {
    log::error("unable to locate file '{}'", filename);
    return "";
  }
  std::string encrypted_hash;
  if (!base::ReadFileToString(path, &encrypted_hash)) {
    log::error("unable to read file '{}'", filename);
  }
  return encrypted_hash;
}

std::unique_ptr<config_t> config_new_clone(const config_t& src) {
  std::unique_ptr<config_t> ret = config_new_empty();

  for (const section_t& sec : src.sections) {
    for (const entry_t& entry : sec.entries) {
      config_set_string(ret.get(), sec.name, entry.key, entry.value);
    }
  }

  return ret;
}

bool config_has_section(const config_t& config, const std::string& section) {
  return (section_find(config, section) != config.sections.end());
}

bool config_has_key(const config_t& config, const std::string& section,
                    const std::string& key) {
  return (entry_find(config, section, key) != nullptr);
}

int config_get_int(const config_t& config, const std::string& section,
                   const std::string& key, int def_value) {
  const entry_t* entry = entry_find(config, section, key);
  if (!entry) return def_value;

  char* endptr;
  int ret = strtol(entry->value.c_str(), &endptr, 0);
  return (*endptr == '\0') ? ret : def_value;
}

uint64_t config_get_uint64(const config_t& config, const std::string& section,
                           const std::string& key, uint64_t def_value) {
  const entry_t* entry = entry_find(config, section, key);
  if (!entry) return def_value;

  char* endptr;
  uint64_t ret = strtoull(entry->value.c_str(), &endptr, 0);
  return (*endptr == '\0') ? ret : def_value;
}

bool config_get_bool(const config_t& config, const std::string& section,
                     const std::string& key, bool def_value) {
  const entry_t* entry = entry_find(config, section, key);
  if (!entry) return def_value;

  if (entry->value == "true") return true;
  if (entry->value == "false") return false;

  return def_value;
}

const std::string* config_get_string(const config_t& config,
                                     const std::string& section,
                                     const std::string& key,
                                     const std::string* def_value) {
  const entry_t* entry = entry_find(config, section, key);
  if (!entry) return def_value;

  return &entry->value;
}

void config_set_int(config_t* config, const std::string& section,
                    const std::string& key, int value) {
  config_set_string(config, section, key, std::to_string(value));
}

void config_set_uint64(config_t* config, const std::string& section,
                       const std::string& key, uint64_t value) {
  config_set_string(config, section, key, std::to_string(value));
}

void config_set_bool(config_t* config, const std::string& section,
                     const std::string& key, bool value) {
  config_set_string(config, section, key, value ? "true" : "false");
}

void config_set_string(config_t* config, const std::string& section,
                       const std::string& key, const std::string& value) {
  log::assert_that(config != nullptr, "assert failed: config != nullptr");

  auto sec = section_find(*config, section);
  if (sec == config->sections.end()) {
    config->sections.emplace_back(section_t{.name = section});
    sec = std::prev(config->sections.end());
  }

  std::string value_no_newline;
  size_t newline_position = value.find('\n');
  if (newline_position != std::string::npos) {
    value_no_newline = value.substr(0, newline_position);
  } else {
    value_no_newline = value;
  }

  for (entry_t& entry : sec->entries) {
    if (entry.key == key) {
      entry.value = value_no_newline;
      return;
    }
  }

  sec->entries.emplace_back(entry_t{.key = key, .value = value_no_newline});
}

bool config_remove_section(config_t* config, const std::string& section) {
  log::assert_that(config != nullptr, "assert failed: config != nullptr");

  auto sec = section_find(*config, section);
  if (sec == config->sections.end()) return false;

  config->sections.erase(sec);
  return true;
}

bool config_remove_key(config_t* config, const std::string& section,
                       const std::string& key) {
  log::assert_that(config != nullptr, "assert failed: config != nullptr");
  auto sec = section_find(*config, section);
  if (sec == config->sections.end()) return false;

  for (auto entry = sec->entries.begin(); entry != sec->entries.end();
       ++entry) {
    if (entry->key == key) {
      sec->entries.erase(entry);
      return true;
    }
  }

  return false;
}

bool config_save(const config_t& config, const std::string& filename) {
  log::assert_that(!filename.empty(), "assert failed: !filename.empty()");

  // Steps to ensure content of config file gets to disk:
  //
  // 1) Open and write to temp file (e.g. bt_config.conf.new).
  // 2) Flush the stream buffer to the temp file.
  // 3) Sync the temp file to disk with fsync().
  // 4) Rename temp file to actual config file (e.g. bt_config.conf).
  //    This ensures atomic update.
  // 5) Sync directory that has the conf file with fsync().
  //    This ensures directory entries are up-to-date.
  int dir_fd = -1;
  FILE* fp = nullptr;
  std::stringstream serialized;

  // Build temp config file based on config file (e.g. bt_config.conf.new).
  const std::string temp_filename = filename + ".new";

  // Extract directory from file path (e.g. /data/misc/bluedroid).
  const std::string directoryname = base::FilePath(filename).DirName().value();
  if (directoryname.empty()) {
    log::error("error extracting directory from '{}': {}", filename,
               strerror(errno));
    goto error;
  }

  dir_fd = open(directoryname.c_str(), O_RDONLY);
  if (dir_fd < 0) {
    log::error("unable to open dir '{}': {}", directoryname, strerror(errno));
    goto error;
  }

  fp = fopen(temp_filename.c_str(), "wt");
  if (!fp) {
    log::error("unable to write to file '{}': {}", temp_filename,
               strerror(errno));
    goto error;
  }

  for (const section_t& section : config.sections) {
    serialized << "[" << section.name << "]" << std::endl;

    for (const entry_t& entry : section.entries)
      serialized << entry.key << " = " << entry.value << std::endl;

    serialized << std::endl;
  }

  if (fprintf(fp, "%s", serialized.str().c_str()) < 0) {
    log::error("unable to write to file '{}': {}", temp_filename,
               strerror(errno));
    goto error;
  }

  // Flush the stream buffer to the temp file.
  if (fflush(fp) < 0) {
    log::error("unable to write flush buffer to file '{}': {}", temp_filename,
               strerror(errno));
    goto error;
  }

  // Sync written temp file out to disk. fsync() is blocking until data makes it
  // to disk.
  if (fsync(fileno(fp)) < 0) {
    log::warn("unable to fsync file '{}': {}", temp_filename, strerror(errno));
  }

  if (fclose(fp) == EOF) {
    log::error("unable to close file '{}': {}", temp_filename, strerror(errno));
    goto error;
  }
  fp = nullptr;

  // Change the file's permissions to Read/Write by User and Group
  if (chmod(temp_filename.c_str(), S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP) ==
      -1) {
    log::error("unable to change file permissions '{}': {}", filename,
               strerror(errno));
    goto error;
  }

  // Rename written temp file to the actual config file.
  if (rename(temp_filename.c_str(), filename.c_str()) == -1) {
    log::error("unable to commit file '{}': {}", filename, strerror(errno));
    goto error;
  }

  // This should ensure the directory is updated as well.
  if (fsync(dir_fd) < 0) {
    log::warn("unable to fsync dir '{}': {}", directoryname, strerror(errno));
  }

  if (close(dir_fd) < 0) {
    log::error("unable to close dir '{}': {}", directoryname, strerror(errno));
    goto error;
  }

  return true;

error:
  // This indicates there is a write issue.  Unlink as partial data is not
  // acceptable.
  unlink(temp_filename.c_str());
  if (fp) fclose(fp);
  if (dir_fd != -1) close(dir_fd);
  return false;
}

bool checksum_save(const std::string& checksum, const std::string& filename) {
  log::assert_that(!checksum.empty(), "checksum cannot be empty");
  log::assert_that(!filename.empty(), "filename cannot be empty");

  // Steps to ensure content of config checksum file gets to disk:
  //
  // 1) Open and write to temp file (e.g.
  // bt_config.conf.encrypted-checksum.new). 2) Sync the temp file to disk with
  // fsync(). 3) Rename temp file to actual config checksum file (e.g.
  // bt_config.conf.encrypted-checksum).
  //    This ensures atomic update.
  // 4) Sync directory that has the conf file with fsync().
  //    This ensures directory entries are up-to-date.
  FILE* fp = nullptr;
  int dir_fd = -1;

  // Build temp config checksum file based on config checksum file (e.g.
  // bt_config.conf.encrypted-checksum.new).
  const std::string temp_filename = filename + ".new";
  base::FilePath path(temp_filename);

  // Extract directory from file path (e.g. /data/misc/bluedroid).
  const std::string directoryname = base::FilePath(filename).DirName().value();
  if (directoryname.empty()) {
    log::error("error extracting directory from '{}': {}", filename,
               strerror(errno));
    goto error2;
  }

  dir_fd = open(directoryname.c_str(), O_RDONLY);
  if (dir_fd < 0) {
    log::error("unable to open dir '{}': {}", directoryname, strerror(errno));
    goto error2;
  }

  if (base::WriteFile(path, checksum.data(), checksum.size()) !=
      (int)checksum.size()) {
    log::error("unable to write file '{}", filename);
    goto error2;
  }

  fp = fopen(temp_filename.c_str(), "rb");
  if (!fp) {
    log::error("unable to write to file '{}': {}", temp_filename,
               strerror(errno));
    goto error2;
  }

  // Sync written temp file out to disk. fsync() is blocking until data makes it
  // to disk.
  if (fsync(fileno(fp)) < 0) {
    log::warn("unable to fsync file '{}': {}", temp_filename, strerror(errno));
  }

  if (fclose(fp) == EOF) {
    log::error("unable to close file '{}': {}", temp_filename, strerror(errno));
    goto error2;
  }
  fp = nullptr;

  // Change the file's permissions to Read/Write by User and Group
  if (chmod(temp_filename.c_str(), S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP) ==
      -1) {
    log::error("unable to change file permissions '{}': {}", filename,
               strerror(errno));
    goto error2;
  }

  // Rename written temp file to the actual config file.
  if (rename(temp_filename.c_str(), filename.c_str()) == -1) {
    log::error("unable to commit file '{}': {}", filename, strerror(errno));
    goto error2;
  }

  // This should ensure the directory is updated as well.
  if (fsync(dir_fd) < 0) {
    log::warn("unable to fsync dir '{}': {}", directoryname, strerror(errno));
  }

  if (close(dir_fd) < 0) {
    log::error("unable to close dir '{}': {}", directoryname, strerror(errno));
    goto error2;
  }

  return true;

error2:
  // This indicates there is a write issue.  Unlink as partial data is not
  // acceptable.
  unlink(temp_filename.c_str());
  if (fp) fclose(fp);
  if (dir_fd != -1) close(dir_fd);
  return false;
}

static char* trim(char* str) {
  while (isspace(*str)) ++str;

  if (!*str) return str;

  char* end_str = str + strlen(str) - 1;
  while (end_str > str && isspace(*end_str)) --end_str;

  end_str[1] = '\0';
  return str;
}

static bool config_parse(FILE* fp, config_t* config) {
  log::assert_that(fp != nullptr, "assert failed: fp != nullptr");
  log::assert_that(config != nullptr, "assert failed: config != nullptr");

  int line_num = 0;
  char line[4096];
  char section[4096];
  strcpy(section, CONFIG_DEFAULT_SECTION);

  while (fgets(line, sizeof(line), fp)) {
    char* line_ptr = trim(line);
    ++line_num;

    // Skip blank and comment lines.
    if (*line_ptr == '\0' || *line_ptr == '#') continue;

    if (*line_ptr == '[') {
      size_t len = strlen(line_ptr);
      if (line_ptr[len - 1] != ']') {
        log::verbose("unterminated section name on line {}", line_num);
        return false;
      }
      strncpy(section, line_ptr + 1, len - 2);  // NOLINT (len < 4096)
      section[len - 2] = '\0';
    } else {
      char* split = strchr(line_ptr, '=');
      if (!split) {
        log::verbose("no key/value separator found on line {}", line_num);
        return false;
      }

      *split = '\0';
      config_set_string(config, section, trim(line_ptr), trim(split + 1));
    }
  }
  return true;
}
