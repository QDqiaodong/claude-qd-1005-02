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

/** 附属设备接口 */
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

    /** 详情：回显与列表、舱段子项读同一行已保存记录 */
    @GetMapping("/{id}")
    public ApiResponse<Facility> detail(@PathVariable Long id) {
        return ApiResponse.success(facilityService.get(id));
    }

    @PostMapping
    public ApiResponse<Facility> create(@RequestBody Facility input) {
        return ApiResponse.success("设备已建档", facilityService.create(input));
    }

    /** 档案更新：名称 / 位置 / 所属舱段 / 类型一次落库，返回落库后回读的记录 */
    @PutMapping("/{id}")
    public ApiResponse<Facility> update(@PathVariable Long id, @RequestBody Facility input) {
        return ApiResponse.success("设备档案已保存", facilityService.update(id, input));
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
