package com.utility.tunnel.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.utility.tunnel.entity.Facility;

@Mapper
public interface FacilityMapper {

    int insert(Facility facility);

    List<Facility> findBySegmentId(@Param("segmentId") Long segmentId);

    Facility selectById(@Param("id") Long id);

    /** 行锁读取：更新 / 报故障 / 恢复时与并发修改互斥 */
    Facility selectByIdForUpdate(@Param("id") Long id);

    List<Facility> findAll();

    /**
     * 更新档案字段（名称 / 位置 / 所属舱段），带行版本条件。
     *
     * @param id           设备 id
     * @param expectedVersion 打开表单时读到的 updatedAt；null 表示从未更新过
     * @param nextVersion  本次写入的新版本时间
     * @return 影响行数：版本不匹配（陈旧提交 / 并发已改）时为 0，由业务层报错
     */
    int updateProfile(@Param("id") Long id,
                      @Param("segmentId") Long segmentId,
                      @Param("facilityName") String facilityName,
                      @Param("location") String location,
                      @Param("expectedVersion") LocalDateTime expectedVersion,
                      @Param("nextVersion") LocalDateTime nextVersion);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int countAll();

    int countByStatus(@Param("status") String status);
}
