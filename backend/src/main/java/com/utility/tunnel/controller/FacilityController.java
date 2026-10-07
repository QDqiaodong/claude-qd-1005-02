package com.utility.tunnel.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.utility.tunnel.common.ApiResponse;
import com.utility.tunnel.entity.Facility;
import com.utility.tunnel.service.FacilityService;

/** 附属设备（设施）接口 */
@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityService facilityService;

    public FacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    @GetMapping
    public ApiResponse<List<Facility>> list(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(facilityService.list(segmentId));
    }

    /** 设施详情：名称 / 位置 / 所属舱段与列表来自同一条已保存记录 */
    @GetMapping("/{id}")
    public ApiResponse<Facility> get(@PathVariable Long id) {
        return ApiResponse.success(facilityService.get(id));
    }

    @PostMapping
    public ApiResponse<Facility> create(@RequestBody Facility input) {
        return ApiResponse.success("设施已建档", facilityService.create(input));
    }

    /** 更新档案：名称 / 位置 / 所属舱段（请求体须带回 updatedAt 版本，防旧值写回） */
    @PutMapping("/{id}")
    public ApiResponse<Facility> update(@PathVariable Long id, @RequestBody Facility input) {
        input.setId(id);
        return ApiResponse.success("设施档案已保存", facilityService.update(input));
    }

    /** 报故障 */
    @PostMapping("/{id}/fault")
    public ApiResponse<Facility> reportFault(@PathVariable Long id) {
        return ApiResponse.success("已报故障", facilityService.reportFault(id));
    }

    /** 修复恢复 */
    @PostMapping("/{id}/restore")
    public ApiResponse<Facility> restore(@PathVariable Long id) {
        return ApiResponse.success("已恢复", facilityService.restore(id));
    }
}
