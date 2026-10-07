package com.utility.tunnel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 设施接口 HTTP 回归：建档 / 更新 / 详情 / 重复提交。
 * 重点验证 updatedAt 版本经 JSON 往返后仍能精确匹配（DATETIME(3) 毫秒精度）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FacilityControllerRegressionTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createUpdateGetAndResubmitFlow() throws Exception {
        // 建档
        String createBody = "{\"segmentId\":1,\"facilityName\":\"HTTP回归风机\",\"facilityType\":\"风机\","
                + "\"location\":\"HTTP旧位置\",\"status\":\"正常\"}";
        MvcResult createdRes = mockMvc.perform(post("/api/facilities")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.facilityName").value("HTTP回归风机"))
                .andExpect(jsonPath("$.data.location").value("HTTP旧位置"))
                .andExpect(jsonPath("$.data.segCode").value("SEG-01"))
                .andReturn();
        JsonNode created = objectMapper.readTree(createdRes.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long id = created.path("data").path("id").asLong();

        // 首次更新（版本为 null）
        String updateBody = "{\"segmentId\":2,\"facilityName\":\"HTTP回归风机-改\",\"facilityType\":\"风机\","
                + "\"location\":\"HTTP新位置\",\"status\":\"正常\",\"updatedAt\":null}";
        MvcResult updatedRes = mockMvc.perform(put("/api/facilities/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityName").value("HTTP回归风机-改"))
                .andExpect(jsonPath("$.data.location").value("HTTP新位置"))
                .andExpect(jsonPath("$.data.segmentId").value(2))
                .andExpect(jsonPath("$.data.segCode").value("SEG-02"))
                .andExpect(jsonPath("$.data.updatedAt").exists())
                .andReturn();
        JsonNode updated = objectMapper.readTree(updatedRes.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String version = updated.path("data").path("updatedAt").asText();

        // 详情回显 = 已保存值
        mockMvc.perform(get("/api/facilities/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityName").value("HTTP回归风机-改"))
                .andExpect(jsonPath("$.data.location").value("HTTP新位置"))
                .andExpect(jsonPath("$.data.segmentId").value(2))
                .andExpect(jsonPath("$.data.segCode").value("SEG-02"))
                .andExpect(jsonPath("$.data.updatedAt").value(version));

        // 用旧版本（null）重复提交：400，旧值不得写回
        mockMvc.perform(put("/api/facilities/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
        mockMvc.perform(get("/api/facilities/" + id))
                .andExpect(jsonPath("$.data.facilityName").value("HTTP回归风机-改"))
                .andExpect(jsonPath("$.data.segmentId").value(2));

        // 携带新版本再改：成功（验证 JSON 毫秒时间戳往返）
        String update2 = "{\"segmentId\":2,\"facilityName\":\"HTTP回归风机-改2\",\"facilityType\":\"风机\","
                + "\"location\":\"HTTP新位置2\",\"status\":\"正常\",\"updatedAt\":\"" + version + "\"}";
        mockMvc.perform(put("/api/facilities/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(update2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityName").value("HTTP回归风机-改2"))
                .andExpect(jsonPath("$.data.location").value("HTTP新位置2"));

        // 按旧舱段筛选不应再出现，新舱段筛选应出现
        mockMvc.perform(get("/api/facilities").param("segmentId", "1"))
                .andExpect(jsonPath("$.data[?(@.id==" + id + ")]").doesNotExist());
        mockMvc.perform(get("/api/facilities").param("segmentId", "2"))
                .andExpect(jsonPath("$.data[?(@.id==" + id + ")].facilityName").value("HTTP回归风机-改2"));

        // 详情不存在：400
        mockMvc.perform(get("/api/facilities/99999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void duplicateCreateReturns400AndKeepsSingleRow() throws Exception {
        String body = "{\"segmentId\":1,\"facilityName\":\"HTTP重复风机\",\"facilityType\":\"风机\","
                + "\"location\":\"x\",\"status\":\"正常\"}";
        mockMvc.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));

        MvcResult listRes = mockMvc.perform(get("/api/facilities").param("segmentId", "1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = objectMapper.readTree(listRes.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        int count = 0;
        for (JsonNode row : rows) {
            if ("HTTP重复风机".equals(row.path("facilityName").asText())) {
                count++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }

    @Test
    void faultTwiceSecondCallRejected() throws Exception {
        String body = "{\"segmentId\":1,\"facilityName\":\"HTTP故障风机\",\"facilityType\":\"风机\","
                + "\"location\":\"x\",\"status\":\"正常\"}";
        MvcResult res = mockMvc.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        long id = objectMapper.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("id").asLong();

        mockMvc.perform(post("/api/facilities/" + id + "/fault")).andExpect(status().isOk());
        mockMvc.perform(post("/api/facilities/" + id + "/fault"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
        mockMvc.perform(get("/api/facilities/" + id))
                .andExpect(jsonPath("$.data.status").value("故障"));
    }
}
