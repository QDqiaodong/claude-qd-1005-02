package com.utility.tunnel.entity;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 附属设备：保障管廊运营的机电设备。
 *
 * <p>facilityName 为设备名称、location 为安装位置、segmentId 为所属舱段——
 * 三项档案字段只能通过更新接口落库，列表 / 详情 / 舱段子项查询都从同一条已保存行读取。
 * facilityType ∈ {风机, 排水泵, 照明, 监控}；status ∈ {正常, 故障}。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class Facility {

    private Long id;

    /** 所属舱段 id */
    private Long segmentId;

    /** 设备名称（必填） */
    private String facilityName;

    /** 设备类型：风机 / 排水泵 / 照明 / 监控 */
    private String facilityType;

    /** 安装位置 */
    private String location;

    /** 正常 / 故障 */
    private String status;

    private LocalDateTime createdAt;

    /**
     * 行版本：每次更新刷新；更新 UPDATE 带版本条件，陈旧表单 / 并发提交影响 0 行，
     * 不会把旧值写回。
     */
    private LocalDateTime updatedAt;

    // ---- 列表 / 详情 JOIN 舱段带出的展示字段，不参与落库 ----

    /** 所属舱段编码（JOIN tunnel_segment） */
    private String segCode;

    /** 所属舱段名称（JOIN tunnel_segment） */
    private String segName;
}
