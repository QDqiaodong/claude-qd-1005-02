-- 回归测试种子数据：与生产种子同口径（4 舱段 / 4 管线 / 3 巡检点 / 3 设施 / 3 风险事件）
SET MODE MySQL;

INSERT INTO tunnel_segment (id, seg_code, seg_name, shape, length_m, capacity_slots, status, created_at) VALUES
(1, 'SEG-01', '中央综合舱·北段', '矩形', 1200.00, 6, '运营', TIMESTAMP '2026-07-01 08:00:00'),
(2, 'SEG-02', '中央综合舱·南段', '圆形',  980.00,  4, '运营', TIMESTAMP '2026-07-01 08:00:00'),
(3, 'SEG-03', '电力舱',          '矩形',  760.00, 8, '维护', TIMESTAMP '2026-07-01 08:00:00'),
(4, 'SEG-04', '燃气舱',          '马蹄形', 540.00,  8, '停用', TIMESTAMP '2026-07-01 08:00:00');

-- SEG-01 电力高压(2)+通信(1)=3/6；SEG-02 排水 1/4；SEG-04 燃气中压
INSERT INTO pipeline (id, segment_id, line_type, diameter_mm, pressure_level, status, created_at) VALUES
(1, 1, '电力', 300, '高压', '在用', TIMESTAMP '2026-07-01 08:00:00'),
(2, 1, '通信', 100, NULL, '在用', TIMESTAMP '2026-07-01 08:00:00'),
(3, 2, '排水', 500, NULL, '在用', TIMESTAMP '2026-07-01 08:00:00'),
(4, 4, '燃气', 200, '中压', '在用', TIMESTAMP '2026-07-01 08:00:00');

INSERT INTO patrol_point (id, segment_id, point_code, location, last_patrol_at, created_at) VALUES
(1, 1, 'P-01', '桩号 K0+100', TIMESTAMP '2026-10-01 08:00:00', TIMESTAMP '2026-07-01 08:00:00'),
(2, 2, 'P-02', '桩号 K1+200', TIMESTAMP '2026-09-28 08:00:00', TIMESTAMP '2026-07-01 08:00:00'),
(3, 3, 'P-03', '桩号 K2+000', TIMESTAMP '2026-09-24 08:00:00', TIMESTAMP '2026-07-01 08:00:00');

INSERT INTO facility (id, segment_id, facility_name, facility_type, location, status, created_at, updated_at) VALUES
(1, 1, '北段 1# 送风机组',   '风机',   '桩号 K0+050 舱顶',   '正常', TIMESTAMP '2026-07-01 08:00:00', NULL),
(2, 1, '北段 2# 集水坑排水泵', '排水泵', '桩号 K0+300 集水坑', '故障', TIMESTAMP '2026-07-01 08:00:00', NULL),
(3, 4, '燃气舱可燃气体监控', '监控',   '桩号 K3+800 舱壁',   '故障', TIMESTAMP '2026-07-01 08:00:00', NULL);

INSERT INTO risk_event (id, segment_id, source_type, source_id, severity, description, found_at,
                        status, handle_note, resolved_by, resolved_at, created_at) VALUES
(1, 1, '巡检点',   1, '一般', 'K0+100 处照明回路异响，光强波动', TIMESTAMP '2026-10-01 08:00:00',
    '已解除', '现场复位回路并复测正常', '张伟', TIMESTAMP '2026-10-02 10:00:00',
    TIMESTAMP '2026-10-01 08:00:00'),
(2, 3, '巡检点',   3, '较大', '电力舱电缆接头温升偏高，需停电复紧', TIMESTAMP '2026-10-05 08:00:00',
    '处理中', '已安排检修班组进场', NULL, NULL,
    TIMESTAMP '2026-10-05 08:00:00'),
(3, 4, '附属设备', 3, '重大', '燃气舱监控设备故障，存在监测盲区', TIMESTAMP '2026-10-06 08:00:00',
    '待处置', NULL, NULL, NULL,
    TIMESTAMP '2026-10-06 08:00:00');
