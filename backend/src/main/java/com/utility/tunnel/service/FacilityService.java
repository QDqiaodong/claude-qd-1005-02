package com.utility.tunnel.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.utility.tunnel.common.BizException;
import com.utility.tunnel.common.Dict;
import com.utility.tunnel.entity.Facility;
import com.utility.tunnel.mapper.FacilityMapper;
import com.utility.tunnel.mapper.TunnelSegmentMapper;

/**
 * 附属设备业务。
 *
 * <p>核心规则：设备报故障将 status 置为「故障」；修复后恢复为「正常」。
 * 设备类型与状态需在字典范围内。</p>
 *
 * <p>档案编辑（名称 / 位置 / 所属舱段 / 类型）：四项在同一条 UPDATE 中写入同一行，
 * 落库后回读该行返回，保证接口响应、详情回显、列表刷新看到的都是同一条已保存记录；
 * 更新按 id 定位，重复提交只会重复覆盖同一行，不会生成重复设备；
 * status 不在编辑范围内（只能经报故障 / 恢复流转），编辑不会把旧状态写回。</p>
 */
@Service
public class FacilityService {

    private final FacilityMapper facilityMapper;
    private final TunnelSegmentMapper segmentMapper;

    public FacilityService(FacilityMapper facilityMapper, TunnelSegmentMapper segmentMapper) {
        this.facilityMapper = facilityMapper;
        this.segmentMapper = segmentMapper;
    }

    public List<Facility> list(Long segmentId) {
        if (segmentId != null) {
            return facilityMapper.findBySegmentId(segmentId);
        }
        return facilityMapper.findAll();
    }

    public Facility get(Long id) {
        Facility f = facilityMapper.selectById(id);
        if (f == null) {
            throw new BizException("设备不存在：" + id);
        }
        return f;
    }

    @Transactional
    public Facility create(Facility input) {
        validateArchive(input);
        if (input.getStatus() == null || !Dict.isValidFacilityStatus(input.getStatus())) {
            throw new BizException("设备状态不合法，应为：正常 / 故障");
        }
        Facility f = new Facility();
        f.setSegmentId(input.getSegmentId());
        f.setName(input.getName().trim());
        f.setFacilityType(input.getFacilityType());
        f.setLocation(normalizeLocation(input.getLocation()));
        f.setStatus(input.getStatus());
        facilityMapper.insert(f);
        return f;
    }

    /**
     * 档案更新：名称 / 位置 / 所属舱段 / 类型一次落库到同一行。
     *
     * <p>幂等：按 id 的 UPDATE 重复提交结果相同，不产生重复记录；
     * 返回落库后回读的整行，调用方（前端回显）拿到的即库内已保存记录。</p>
     */
    @Transactional
    public Facility update(Long id, Facility input) {
        Facility existing = facilityMapper.selectById(id);
        if (existing == null) {
            throw new BizException("设备不存在：" + id);
        }
        validateArchive(input);
        Facility f = new Facility();
        f.setId(id);
        f.setSegmentId(input.getSegmentId());
        f.setName(input.getName().trim());
        f.setFacilityType(input.getFacilityType());
        f.setLocation(normalizeLocation(input.getLocation()));
        if (facilityMapper.update(f) != 1) {
            // 行在校验后被并发删除等异常：影响行数不为 1 即失败，不落半条档案
            throw new BizException("设备保存失败，请刷新后重试");
        }
        // 回读已保存记录返回，保证响应与库内一致（详情 / 列表读的也是这一行）
        return facilityMapper.selectById(id);
    }

    /** 报故障 */
    @Transactional
    public Facility reportFault(Long id) {
        Facility f = get(id);
        f.setStatus("故障");
        facilityMapper.updateStatus(f.getId(), "故障");
        return f;
    }

    /** 修复恢复 */
    @Transactional
    public Facility restore(Long id) {
        Facility f = get(id);
        f.setStatus("正常");
        facilityMapper.updateStatus(f.getId(), "正常");
        return f;
    }

    /** 建档 / 编辑共用的档案校验：所属舱段必须存在、名称必填、类型须在字典内 */
    private void validateArchive(Facility input) {
        if (input.getSegmentId() == null) {
            throw new BizException("必须指定所属舱段");
        }
        if (segmentMapper.selectById(input.getSegmentId()) == null) {
            throw new BizException("所属舱段不存在：" + input.getSegmentId());
        }
        if (input.getName() == null || input.getName().isBlank()) {
            throw new BizException("设备名称不能为空");
        }
        if (input.getFacilityType() == null || !Dict.isValidFacilityType(input.getFacilityType())) {
            throw new BizException("设备类型不合法，应为：风机 / 排水泵 / 照明 / 监控");
        }
    }

    /** 空白位置按未填写（null）处理 */
    private String normalizeLocation(String location) {
        return (location == null || location.isBlank()) ? null : location.trim();
    }
}
