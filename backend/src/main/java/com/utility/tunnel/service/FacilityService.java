package com.utility.tunnel.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.utility.tunnel.common.BizException;
import com.utility.tunnel.common.Dict;
import com.utility.tunnel.entity.Facility;
import com.utility.tunnel.mapper.FacilityMapper;
import com.utility.tunnel.mapper.RiskEventMapper;
import com.utility.tunnel.mapper.TunnelSegmentMapper;

/**
 * 附属设备（设施）业务。
 *
 * <p>核心规则：
 * 1) 名称（facilityName）、安装位置（location）、所属舱段（segmentId）三项档案只能经
 *    {@link #update} 落库，列表 / 详情 / 舱段子项查询共用同一份字段与同一条 JOIN，
 *    回显永远来自同一条已保存记录；
 * 2) 同一舱段内设施名称唯一（DB 唯一索引兜底）：建档 / 改名 / 改舱段撞名直接拒绝，
 *    重复点击保存不会产生重复设施；
 * 3) 更新走「行锁 + updated_at 行版本」：UPDATE 带版本条件，陈旧表单或并发提交影响 0 行
 *    即回滚报错，旧值不会写回；
 * 4) 设施上挂着未解除风险事件（封控中）时不允许改舱段，封控对象不能被“挪走”而绕过封控；
 *    入廊管线校核链在管线模块独立生效，设施任何操作都不触碰它；
 * 5) 设备报故障将 status 置为「故障」；修复后恢复为「正常」，设备类型与状态须在字典范围内。</p>
 */
@Service
public class FacilityService {

    private final FacilityMapper facilityMapper;
    private final TunnelSegmentMapper segmentMapper;
    private final RiskEventMapper riskEventMapper;

    public FacilityService(FacilityMapper facilityMapper,
                           TunnelSegmentMapper segmentMapper,
                           RiskEventMapper riskEventMapper) {
        this.facilityMapper = facilityMapper;
        this.segmentMapper = segmentMapper;
        this.riskEventMapper = riskEventMapper;
    }

    public List<Facility> list(Long segmentId) {
        if (segmentId != null) {
            return facilityMapper.findBySegmentId(segmentId);
        }
        return facilityMapper.findAll();
    }

    /** 详情：名称 / 位置 / 所属舱段与列表走同一查询，不存在多源回显 */
    public Facility get(Long id) {
        Facility f = facilityMapper.selectById(id);
        if (f == null) {
            throw new BizException("设施不存在：" + id);
        }
        return f;
    }

    @Transactional
    public Facility create(Facility input) {
        Long segmentId = input.getSegmentId();
        if (segmentId == null) {
            throw new BizException("必须指定所属舱段");
        }
        if (segmentMapper.selectById(segmentId) == null) {
            throw new BizException("所属舱段不存在：" + segmentId);
        }
        String name = normalizeName(input.getFacilityName());
        String type = input.getFacilityType();
        if (!Dict.isValidFacilityType(type)) {
            throw new BizException("设备类型不合法，应为：风机 / 排水泵 / 照明 / 监控");
        }
        if (input.getStatus() == null || !Dict.isValidFacilityStatus(input.getStatus())) {
            throw new BizException("设备状态不合法，应为：正常 / 故障");
        }
        Facility f = new Facility();
        f.setSegmentId(segmentId);
        f.setFacilityName(name);
        f.setFacilityType(type);
        f.setLocation(normalizeLocation(input.getLocation()));
        f.setStatus(input.getStatus());
        try {
            facilityMapper.insert(f);
        } catch (DuplicateKeyException e) {
            // 唯一索引（舱段 + 名称）兜底：重复提交建档不会插入第二条
            throw new BizException("该舱段下已存在同名设施「" + name + "」，请勿重复建档");
        }
        return facilityMapper.selectById(f.getId());
    }

    /**
     * 更新设施档案：名称 / 位置 / 所属舱段。
     *
     * <p>表单必须带回打开详情时读到的 updatedAt 作为版本；
     * 持设施行锁 + 源/目标舱段行锁执行，与风险登记（持舱段行锁）互斥，
     * 既不会在封控期间把设施挪舱，也不会把旧值覆盖回去。</p>
     */
    @Transactional
    public Facility update(Facility input) {
        if (input.getId() == null) {
            throw new BizException("缺少设施 id");
        }
        Facility current = facilityMapper.selectByIdForUpdate(input.getId());
        if (current == null) {
            throw new BizException("设施不存在：" + input.getId());
        }
        // 版本校验：陈旧表单 / 重复点击导致的二次提交在此被挡下
        if (!Objects.equals(current.getUpdatedAt(), input.getUpdatedAt())) {
            throw new BizException("设施信息已被他人修改，请刷新后重新编辑");
        }

        Long targetSegmentId = input.getSegmentId();
        if (targetSegmentId == null) {
            throw new BizException("必须指定所属舱段");
        }
        String name = normalizeName(input.getFacilityName());
        String location = normalizeLocation(input.getLocation());

        boolean segmentChanged = !targetSegmentId.equals(current.getSegmentId());
        if (segmentChanged) {
            // 锁源/目标舱段行（按 id 排序加锁避免死锁），与风险登记互斥
            lockSegmentsInOrder(current.getSegmentId(), targetSegmentId);
            if (segmentMapper.selectById(targetSegmentId) == null) {
                throw new BizException("所属舱段不存在：" + targetSegmentId);
            }
            // 封控闭环：设施上有未解除风险事件时不得改舱段，防止封控对象被挪走
            int open = riskEventMapper.countUnresolvedByFacility(input.getId());
            if (open > 0) {
                throw new BizException("该设施存在 " + open
                        + " 条未解除风险事件，封控解除前不能调整所属舱段");
            }
        }

        boolean unchanged = segmentChanged == false
                && name.equals(current.getFacilityName())
                && Objects.equals(location, current.getLocation());
        if (unchanged) {
            // 内容未变的重复提交：幂等返回当前已保存记录，不再写一次
            return current;
        }

        // DATETIME(3)：版本截到毫秒，保证回传后能与库内版本精确匹配
        LocalDateTime nextVersion = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        int affected;
        try {
            affected = facilityMapper.updateProfile(input.getId(), targetSegmentId, name, location,
                    current.getUpdatedAt(), nextVersion);
        } catch (DuplicateKeyException e) {
            throw new BizException("目标舱段下已存在同名设施「" + name + "」，请调整名称或舱段");
        }
        if (affected == 0) {
            // 行锁内版本已比对，0 行只可能来自并发：整体回滚，旧值不落库
            throw new BizException("设施信息已被他人修改，请刷新后重新编辑");
        }
        // 回库重读：返回值与后续列表 / 详情 / 舱段子项是同一条已保存记录
        return facilityMapper.selectById(input.getId());
    }

    /** 报故障 */
    @Transactional
    public Facility reportFault(Long id) {
        Facility f = get(id);
        if (facilityMapper.updateStatus(f.getId(), "故障") == 0) {
            // 并发重复上报或已是故障：不重复写库
            throw new BizException("设施已处于故障状态，请勿重复上报");
        }
        return get(id);
    }

    /** 修复恢复 */
    @Transactional
    public Facility restore(Long id) {
        Facility f = get(id);
        if (facilityMapper.updateStatus(f.getId(), "正常") == 0) {
            throw new BizException("设施当前为正常状态，无需恢复");
        }
        return get(id);
    }

    /** 按 id 升序依次锁舱段行，避免跨舱段操作与风险登记之间出现死锁 */
    private void lockSegmentsInOrder(Long first, Long second) {
        long lo = Math.min(first, second);
        long hi = Math.max(first, second);
        segmentMapper.selectByIdForUpdate(lo);
        if (hi != lo) {
            segmentMapper.selectByIdForUpdate(hi);
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("设施名称不能为空");
        }
        return name.trim();
    }

    private String normalizeLocation(String location) {
        return (location == null || location.isBlank()) ? null : location.trim();
    }
}
