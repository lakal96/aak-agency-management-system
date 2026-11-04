package lk.aak.agency.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bad input to the REST API gives a clear 4xx message - never a 500, a stack trace or SQL. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user("admin").roles("ADMIN")));
    }

    @Test
    void textWhereANumberIsExpected_isA400() throws Exception {
        admin(get("/api/v1/customers/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("id")));
        admin(get("/api/v1/customers").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("page")));
        admin(get("/api/v1/customers").param("size", "lots"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void garbageDateInAQuery_isA400() throws Exception {
        admin(get("/api/v1/reports/summary").param("fromDate", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("fromDate")));
    }

    @Test
    void missingRequiredParameter_isA400() throws Exception {
        admin(post("/api/v1/products/import"))
                .andExpect(status().is4xxClientError());
        admin(post("/api/v1/delivery-trips/1/invoices/1/delivery-status"))
                .andExpect(status().is4xxClientError());
        admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/delivery-trips/1/invoices/1/delivery-status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("status")));
    }

    @Test
    void malformedJson_isA400_withoutParserDetails() throws Exception {
        admin(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", not(containsString("com.fasterxml"))))
                .andExpect(jsonPath("$.message", not(containsString("Unexpected character"))));
        admin(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content("{\"creditLimit\":\"lots\",\"customerName\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordNotFoundForPdfAndQr_is404_evenWhenTheBrowserAsksForAPdfOrImage() throws Exception {
        admin(get("/api/v1/sales-invoices/987654/pdf").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound());
        admin(get("/api/v1/purchase-invoices/987654/pdf").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound());
        admin(get("/api/v1/customers/987654/qr-code").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotFound());
        admin(get("/api/v1/customers/987654"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationErrorNamesTheFieldAndTheProblem() throws Exception {
        admin(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content("{\"customerName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.customerName").exists());
    }

    @Test
    void pageSizeIsCappedAt200() throws Exception {
        admin(get("/api/v1/customers").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
    }
}
