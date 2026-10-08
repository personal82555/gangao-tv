#!/usr/bin/env python3
"""CardAuth processOrderPaid 补丁：无bot_qq订单 → 自动发卡并绑定机器码"""
import sys

P = sys.argv[1] if len(sys.argv) > 1 else 'OrderController.php'
src = open(P).read()

anchor = """            // 更新订单状态
            $db->execute(
                "UPDATE {$db->table('orders')} SET status = 'paid', paid_at = NOW() WHERE id = ?",
                [$orderId]
            );"""

assert anchor in src, "anchor NOT FOUND"

patch = """            } else {
                // ===== 无 bot_qq 订单：自动发卡并绑定机器码（IPTV App 直购闭环）=====
                // contact_info 约定格式: "MACHINE:TV-XXXX" （App下单时带上电视机器码）
                $machineId = '';
                if (preg_match('/MACHINE:([A-Za-z0-9\\-_]+)/', $order['contact_info'] ?? '', $mm)) {
                    $machineId = $mm[1];
                }
                // 从同项目同卡类型的未用卡池取一张
                $card = $db->fetch(
                    "SELECT * FROM {$db->table('cards')} WHERE project_id = ? AND card_type_id = ? AND status = 'unused' ORDER BY id ASC LIMIT 1",
                    [$order['project_id'], $order['card_type_id']]
                );
                if ($card) {
                    $expireTime = null;
                    if ($durationDays > 0) {
                        $expireTime = date('Y-m-d H:i:s', strtotime("+{$durationDays} days"));
                    }
                    $bindInfo = json_encode([
                        'machine_id'    => $machineId ?: 'AUTO-' . $order['order_no'],
                        'ip'            => '',
                        'device_info'   => 'auto-delivery',
                        'first_bind_at' => date('Y-m-d H:i:s'),
                        'order_no'      => $order['order_no'],
                    ], JSON_UNESCAPED_UNICODE);
                    $db->execute(
                        "UPDATE {$db->table('cards')} SET status = 'used', bind_info = ?, bound_at = NOW(), expire_time = ? WHERE id = ?",
                        [$bindInfo, $expireTime, $card['id']]
                    );
                    // 订单关联该卡，查询接口可显示
                    $db->execute(
                        "UPDATE {$db->table('orders')} SET card_id = ? WHERE id = ?",
                        [$card['id'], $orderId]
                    );
                }
                // 无未用卡池时订单仍标 paid（余卡告警交给管理端）
            }

            // 更新订单状态
            $db->execute(
                "UPDATE {$db->table('orders')} SET status = 'paid', paid_at = NOW() WHERE id = ?",
                [$orderId]
            );"""

src = src.replace(anchor, patch, 1)
open(P, 'w').write(src)
print("PATCHED OK")
