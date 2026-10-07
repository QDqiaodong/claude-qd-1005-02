package com.utility.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.utility.tunnel.common.BizException;
import com.utility.tunnel.entity.Facility;
import com.utility.tunnel.entity.Pipeline;
import com.utility.tunnel.entity.RiskEvent;
import com.utility.tunnel.entity.SegmentWithChildren;
import com.utility.tunnel.mapper.FacilityMapper;
import com.utility.tunnel.service.FacilityService;
import com.utility.tunnel.service.PipelineService;
import com.utility.tunnel.service.RiskEventService;
import com.utility.tunnel.service.TunnelSegmentService;

/**
 * 设施档案回归：名称 / 位置 / 所属舱段更新后的一致性、重复保存幂等、版本并发、
 * 风险封控与入廊管线校核不被绕过。H2（MySQL 模式）内存库，每个类共享种子数据，
 * 用例只新增本用例专用数据，互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FacilityRegressionTest {

    @Autowired
    private FacilityService facilityService;
    @Autowired
    private FacilityMapper facilityMapper;
    @Autowired
    private TunnelSegmentService segmentService;
    @Autowired
    private RiskEventService riskEventService;
    @Autowired
    private PipelineService pipelineService;

    private Facility buildFacility(long segmentId, String name, String type, String location, String status) {
        Facility f = new Facility();
        f.setSegmentId(segmentId);
        f.setFacilityName(name);
        f.setFacilityType(type);
        f.setLocation(location);
        f.setStatus(status);
        return f;
    }

    /** 1. 更新后列表、详情、按舱段筛选、舱段下钻看到同一行的同一组新值 */
    @Test
    void updateProfileIsConsistentAcrossListDetailFilterAndSegmentDrilldown() {
        Facility created = facilityService.create(
                buildFacility(1L, "回归-送风机 A", "风机", "旧位置 K0+000", "正常"));
        assertNull(created.getUpdatedAt(), "建档时 updatedAt 应为 null");
        assertEquals("SEG-01", created.getSegCode(), "列表查询应 JOIN 带出舱段编码");

        Facility edit = buildFacility(1L, "回归-送风机 A 改", "风机", "新位置 K0+500", "正常");
        edit.setId(created.getId());
        edit.setUpdatedAt(created.getUpdatedAt());
        Facility saved = facilityService.update(edit);
        assertNotNull(saved.getUpdatedAt(), "更新后应生成行版本");
        assertEquals("回归-送风机 A 改", saved.getFacilityName());
        assertEquals("新位置 K0+500", saved.getLocation());
        assertEquals(1L, saved.getSegmentId());
        assertEquals("SEG-01", saved.getSegCode());

        // 列表（全量）
        Facility fromList = facilityService.list(null).stream()
                .filter(f -> f.getId().equals(created.getId())).findFirst().orElseThrow();
        assertEquals("回归-送风机 A 改", fromList.getFacilityName());
        assertEquals("新位置 K0+500", fromList.getLocation());
        assertEquals(1L, fromList.getSegmentId());
        assertEquals("SEG-01", fromList.getSegCode());

        // 详情查询
        Facility detail = facilityService.get(created.getId());
        assertEquals(saved.getFacilityName(), detail.getFacilityName());
        assertEquals(saved.getLocation(), detail.getLocation());
        assertEquals(saved.getSegmentId(), detail.getSegmentId());
        assertEquals(saved.getUpdatedAt(), detail.getUpdatedAt());

        // 按舱段筛选
        Facility filtered = facilityService.list(1L).stream()
                .filter(f -> f.getId().equals(created.getId())).findFirst().orElseThrow();
        assertEquals("回归-送风机 A 改", filtered.getFacilityName());
        assertEquals("新位置 K0+500", filtered.getLocation());

        // 舱段详情下钻（嵌套集合）
        SegmentWithChildren children = segmentService.withChildren(1L);
        Facility drilled = children.getFacilities().stream()
                .filter(f -> f.getId().equals(created.getId())).findFirst().orElseThrow();
        assertEquals("回归-送风机 A 改", drilled.getFacilityName());
        assertEquals("新位置 K0+500", drilled.getLocation());
        assertEquals(1L, drilled.getSegmentId());
    }

    /** 2. 更新所属舱段后，新舱段筛选可见、旧舱段筛选不可见，舱段下钻跟随 */
    @Test
    void movingSegmentUpdatesFilterAndDrilldown() {
        Facility created = facilityService.create(
                buildFacility(2L, "回归-搬迁泵 B", "排水泵", "旧舱 K1+000", "正常"));

        Facility edit = buildFacility(2L, "回归-搬迁泵 B", "排水泵", "新舱 K2+100", "正常");
        edit.setId(created.getId());
        edit.setUpdatedAt(created.getUpdatedAt());
        edit.setSegmentId(3L);
        Facility saved = facilityService.update(edit);
        assertEquals(3L, saved.getSegmentId());
        assertEquals("SEG-03", saved.getSegCode());

        assertTrue(facilityService.list(3L).stream().anyMatch(f -> f.getId().equals(created.getId())));
        assertFalse(facilityService.list(2L).stream().anyMatch(f -> f.getId().equals(created.getId())));
        assertTrue(segmentService.withChildren(3L).getFacilities().stream()
                .anyMatch(f -> f.getId().equals(created.getId())));
        assertFalse(segmentService.withChildren(2L).getFacilities().stream()
                .anyMatch(f -> f.getId().equals(created.getId())));
    }

    /** 3. 重复建档同舱同名被唯一约束拒绝，库里只有一条 */
    @Test
    void duplicateCreateOnSameSegmentAndNameRejected() {
        facilityService.create(buildFacility(1L, "回归-同名风机 C", "风机", "位置 1", "正常"));
        int before = facilityService.list(1L).size();
        BizException ex = assertThrows(BizException.class, () ->
                facilityService.create(buildFacility(1L, "回归-同名风机 C", "照明", "位置 2", "正常")));
        assertTrue(ex.getMessage().contains("同名") || ex.getMessage().contains("重复"));
        assertEquals(before, facilityService.list(1L).size(), "重复建档不得插入第二条");

        // 不同舱段允许同名
        Facility other = facilityService.create(
                buildFacility(2L, "回归-同名风机 C", "风机", "位置 3", "正常"));
        assertNotNull(other.getId());
    }

    /** 4. 内容未变的重复保存幂等：不写新版本、不新增行 */
    @Test
    void unchangedResubmitIsIdempotent() {
        Facility created = facilityService.create(
                buildFacility(1L, "回归-幂等风机 D", "风机", "K0+010", "正常"));
        int total = facilityMapper.countAll();
        LocalDateTime version = created.getUpdatedAt();

        Facility again = buildFacility(1L, "回归-幂等风机 D", "风机", "K0+010", "正常");
        again.setId(created.getId());
        again.setUpdatedAt(version);
        Facility result = facilityService.update(again);

        assertEquals(total, facilityMapper.countAll(), "不得新增设施");
        assertNull(result.getUpdatedAt(), "内容未变不应刷新版本");
        assertEquals(created.getFacilityName(), facilityService.get(created.getId()).getFacilityName());
    }

    /** 5. 携带旧版本（陈旧表单 / 并发已改）的提交被拒，旧值不写回，库里保留新值 */
    @Test
    void staleVersionSubmitIsRejectedAndOldValuesNotWrittenBack() {
        Facility created = facilityService.create(
                buildFacility(1L, "回归-并发风机 E", "风机", "初始位置", "正常"));

        Facility first = buildFacility(1L, "回归-并发风机 E-第一次改", "风机", "第一版位置", "正常");
        first.setId(created.getId());
        first.setUpdatedAt(created.getUpdatedAt());
        Facility firstSaved = facilityService.update(first);
        assertNotNull(firstSaved.getUpdatedAt());

        // 第二个窗口仍持有建档时的旧版本（null），把旧位置写回
        Facility stale = buildFacility(1L, "回归-并发风机 E", "风机", "初始位置", "正常");
        stale.setId(created.getId());
        stale.setUpdatedAt(created.getUpdatedAt());
        BizException ex = assertThrows(BizException.class, () -> facilityService.update(stale));
        assertTrue(ex.getMessage().contains("刷新") || ex.getMessage().contains("修改"));

        Facility after = facilityService.get(created.getId());
        assertEquals("回归-并发风机 E-第一次改", after.getFacilityName(), "旧名称不得写回");
        assertEquals("第一版位置", after.getLocation(), "旧位置不得写回");
        assertEquals(firstSaved.getUpdatedAt(), after.getUpdatedAt());

        // 用新版本继续改可以成功
        Facility second = buildFacility(1L, "回归-并发风机 E-第二次改", "风机", "第二版位置", "正常");
        second.setId(created.getId());
        second.setUpdatedAt(firstSaved.getUpdatedAt());
        Facility secondSaved = facilityService.update(second);
        assertEquals("回归-并发风机 E-第二次改", facilityService.get(created.getId()).getFacilityName());
        assertEquals("第二版位置", facilityService.get(created.getId()).getLocation());
        assertEquals(secondSaved.getUpdatedAt(), facilityService.get(created.getId()).getUpdatedAt());
    }

    /** 6. 设施挂有未解除风险事件（封控中）时禁止改舱段；解除后放行 */
    @Test
    void openRiskLocksFacilitySegmentMove() {
        // 种子设施 #3 在 SEG-04，风险事件 #3 待处置
        Facility f3 = facilityService.get(3L);
        assertEquals(4L, f3.getSegmentId());

        Facility move = buildFacility(4L, f3.getFacilityName(), f3.getFacilityType(), f3.getLocation(), f3.getStatus());
        move.setId(3L);
        move.setUpdatedAt(f3.getUpdatedAt());
        move.setSegmentId(2L);
        BizException ex = assertThrows(BizException.class, () -> facilityService.update(move));
        assertTrue(ex.getMessage().contains("风险事件") || ex.getMessage().contains("封控"));
        assertEquals(4L, facilityService.get(3L).getSegmentId(), "封控中改舱段必须回滚");

        // 名称 / 位置仍可在本舱段内修改（不涉及封控对象迁移）
        Facility rename = buildFacility(4L, "燃气舱可燃气体监控(已检修)", f3.getFacilityType(), "K3+801 舱壁", f3.getStatus());
        rename.setId(3L);
        rename.setUpdatedAt(f3.getUpdatedAt());
        Facility renamed = facilityService.update(rename);
        assertEquals(4L, renamed.getSegmentId());
        assertEquals("燃气舱可燃气体监控(已检修)", renamed.getFacilityName());

        // 解除封控后允许改舱段
        riskEventService.resolve(3L, "更换监控主机并复测正常", "王磊");
        Facility move2 = buildFacility(4L, renamed.getFacilityName(), renamed.getFacilityType(), renamed.getLocation(), renamed.getStatus());
        move2.setId(3L);
        move2.setUpdatedAt(renamed.getUpdatedAt());
        move2.setSegmentId(2L);
        assertEquals(2L, facilityService.update(move2).getSegmentId());
    }

    /** 7. 设施更新不影响入廊管线校核：封控舱仍拒绝管线、容量超限仍拒绝 */
    @Test
    void pipelineGuardsStillEnforced() {
        // SEG-03 有未解除风险事件 #2：任何新管线入廊必须被封控规则拦下
        Pipeline gas = new Pipeline();
        gas.setSegmentId(3L);
        gas.setLineType("燃气");
        gas.setDiameterMm(200);
        gas.setPressureLevel("中压");
        gas.setStatus("在用");
        BizException locked = assertThrows(BizException.class, () -> pipelineService.create(gas));
        assertTrue(locked.getMessage().contains("封控") || locked.getMessage().contains("风险事件"));

        // 预检（不落库）同样拦截
        assertFalse(pipelineService.check(gas).isCompatible());

        // SEG-04 已停用：停用规则必须仍在（先解除其风险再试，证明不是封控规则替它挡的）
        riskEventService.resolve(3L, "处置完成", "王磊");
        Pipeline stopped = new Pipeline();
        stopped.setSegmentId(4L);
        stopped.setLineType("燃气");
        stopped.setDiameterMm(200);
        stopped.setPressureLevel("低压");
        stopped.setStatus("在用");
        BizException stoppedEx = assertThrows(BizException.class, () -> pipelineService.create(stopped));
        assertTrue(stoppedEx.getMessage().contains("停用"));

        // SEG-01 舱容 6、已占 3：再入高压给水(3)+高压电力(2) 第二条应超容量；
        // 且 SEG-01 已有电力，燃气还必须被防爆隔离拦截（优先于容量）
        Pipeline gasIntoPower = new Pipeline();
        gasIntoPower.setSegmentId(1L);
        gasIntoPower.setLineType("燃气");
        gasIntoPower.setDiameterMm(200);
        gasIntoPower.setPressureLevel("低压");
        gasIntoPower.setStatus("在用");
        BizException isolation = assertThrows(BizException.class,
                () -> pipelineService.create(gasIntoPower));
        assertTrue(isolation.getMessage().contains("防爆隔离"));

        int pipelinesBefore = pipelineService.list(null).size();
        Pipeline water = new Pipeline();
        water.setSegmentId(1L);
        water.setLineType("给水");
        water.setDiameterMm(300);
        water.setPressureLevel("高压"); // 占 3，3+3=6 不超限，允许
        water.setStatus("在用");
        pipelineService.create(water);
        Pipeline overflow = new Pipeline();
        overflow.setSegmentId(1L);
        overflow.setLineType("电力");
        overflow.setDiameterMm(200);
        overflow.setPressureLevel("高压"); // 再占 2，6+2>6 超限
        overflow.setStatus("在用");
        BizException capEx = assertThrows(BizException.class, () -> pipelineService.create(overflow));
        assertTrue(capEx.getMessage().contains("容量") || capEx.getMessage().contains("舱容"));
        assertEquals(pipelinesBefore + 1, pipelineService.list(null).size(), "被拒管线不得落库");
    }

    /** 8. 改舱段撞目标舱同名设施被拒；参数非法被拒 */
    @Test
    void moveNameCollisionAndInvalidInputRejected() {
        facilityService.create(buildFacility(2L, "回归-跨段同名 F", "照明", "SEG2 位置", "正常"));
        Facility src = facilityService.create(
                buildFacility(1L, "回归-跨段同名 F", "照明", "SEG1 位置", "正常"));

        Facility move = buildFacility(1L, "回归-跨段同名 F", "照明", "搬过去", "正常");
        move.setId(src.getId());
        move.setUpdatedAt(src.getUpdatedAt());
        move.setSegmentId(2L);
        BizException dup = assertThrows(BizException.class, () -> facilityService.update(move));
        assertTrue(dup.getMessage().contains("同名"));
        assertEquals(1L, facilityService.get(src.getId()).getSegmentId());

        Facility noName = buildFacility(1L, "  ", "风机", "位置", "正常");
        noName.setId(src.getId());
        noName.setUpdatedAt(src.getUpdatedAt());
        assertThrows(BizException.class, () -> facilityService.update(noName));

        Facility badSegment = buildFacility(999L, "回归-跨段同名 F", "照明", "位置", "正常");
        badSegment.setId(src.getId());
        badSegment.setUpdatedAt(src.getUpdatedAt());
        assertThrows(BizException.class, () -> facilityService.update(badSegment));
    }

    /** 9. 报故障 / 恢复的重复与并发提交只生效一次 */
    @Test
    void faultAndRestoreAreSingleEffect() {
        Facility f = facilityService.create(
                buildFacility(1L, "回归-故障风机 G", "风机", "K0+200", "正常"));
        facilityService.reportFault(f.getId());
        assertEquals("故障", facilityService.get(f.getId()).getStatus());
        // 重复报故障：报错且状态不变
        assertThrows(BizException.class, () -> facilityService.reportFault(f.getId()));
        assertEquals("故障", facilityService.get(f.getId()).getStatus());

        facilityService.restore(f.getId());
        assertEquals("正常", facilityService.get(f.getId()).getStatus());
        assertThrows(BizException.class, () -> facilityService.restore(f.getId()));
        assertEquals("正常", facilityService.get(f.getId()).getStatus());

        // 不存在的设施
        assertThrows(BizException.class, () -> facilityService.get(99999L));
    }

    /** 10. 风险登记的发现对象归属校验仍按设施的当前舱段执行（改舱段后旧舱段不能再引用它） */
    @Test
    void riskRegistrationFollowsFacilityCurrentSegment() {
        Facility f = facilityService.create(
                buildFacility(2L, "回归-风险对象 H", "监控", "K1+900", "正常"));
        // 用旧舱段 1 登记、发现对象指向已属 SEG-02 的设施：必须拒绝
        RiskEvent e = new RiskEvent();
        e.setSegmentId(1L);
        e.setSourceType("附属设备");
        e.setSourceId(f.getId());
        e.setSeverity("一般");
        e.setDescription("回归：对象不属于该舱段");
        e.setFoundAt(LocalDateTime.now());
        assertThrows(BizException.class, () -> riskEventService.create(e));

        // 用正确舱段 2 登记：成功，并立即封控该设施的改舱段
        e.setSegmentId(2L);
        RiskEvent saved = riskEventService.create(e);
        assertNotNull(saved.getId());

        Facility move = buildFacility(2L, "回归-风险对象 H", "监控", "想搬走", "正常");
        move.setId(f.getId());
        move.setUpdatedAt(f.getUpdatedAt());
        move.setSegmentId(1L);
        assertThrows(BizException.class, () -> facilityService.update(move));
        assertEquals(2L, facilityService.get(f.getId()).getSegmentId());

        // 解除后 SEG-02 无该未解除事件（实时 COUNT/筛选口径）
        riskEventService.resolve(saved.getId(), "现场处置完成", "赵六");
        long openOnSeg2 = riskEventService.list(2L, null).stream()
                .filter(r -> !"已解除".equals(r.getStatus()))
                .filter(r -> r.getId().equals(saved.getId()))
                .count();
        assertEquals(0, openOnSeg2);
    }
}
