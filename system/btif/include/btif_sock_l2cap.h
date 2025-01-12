/*******************************************************************************
 *  L2CAP Socket Interface
 ******************************************************************************/

#ifndef BTIF_SOCK_L2CAP_H
#define BTIF_SOCK_L2CAP_H

#include <hardware/bluetooth.h>

#include "btif_uid.h"
#include "types/raw_address.h"

bt_status_t btsock_l2cap_init(int handle, uid_set_t* set);
bt_status_t btsock_l2cap_cleanup();
bt_status_t btsock_l2cap_listen(const char* name, int channel, int* sock_fd,
                                int flags, int app_uid);
bt_status_t btsock_l2cap_connect(const RawAddress* bd_addr, int channel,
                                 int* sock_fd, int flags, int app_uid);
void btsock_l2cap_signaled(int fd, int flags, uint32_t user_id);
void on_l2cap_psm_assigned(int id, int psm);
bt_status_t btsock_l2cap_disconnect(const RawAddress* bd_addr);
bt_status_t btsock_l2cap_get_l2cap_local_cid(Uuid& conn_uuid, uint16_t* cid);
bt_status_t btsock_l2cap_get_l2cap_remote_cid(Uuid& conn_uuid, uint16_t* cid);

#endif
