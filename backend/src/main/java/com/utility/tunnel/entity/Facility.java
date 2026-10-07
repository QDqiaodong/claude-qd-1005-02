package com.utility.tunnel.entity;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 附属设备：保障管廊运营的机电设备。
 *
 * <p>facilityType ∈ {风机, 排水泵, 照明, 监控}；status ∈ {正常, 故障}。
 * name / location / segmentId 为档案字段，编辑时三项在同一条 UPDATE 中落库，
 * 详情与列表读取同一行记录；status 只能经报故障 / 恢复流转，编辑表单不参与。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class Facility {

    private Long id;

    /** 所属舱段 id */
    private Long segmentId;

    /** 设备名称（档案名，必填） */
    private String name;

    /** 设备类型：风机 / 排水泵 / 照明 / 监控 */
    private String facilityType;

    /** 安装位置（如 桩号 K0+100 风亭） */
    private String location;

    /** 正常 / 故障 */
    private String status;

    private LocalDateTime createdAt;
}
