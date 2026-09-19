package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.StockAdjustmentRequest;
import lk.aak.agency.dto.api.StockAdjustmentResponse;
import lk.aak.agency.model.StockAdjustment;
import lk.aak.agency.service.StockAdjustmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stock-adjustments")
public class StockAdjustmentApiController {

    private final StockAdjustmentService stockAdjustmentService;

    public StockAdjustmentApiController(StockAdjustmentService stockAdjustmentService) {
        this.stockAdjustmentService = stockAdjustmentService;
    }

    @GetMapping
    public List<StockAdjustmentResponse> list() {
        return stockAdjustmentService.getAllAdjustments().stream().map(StockAdjustmentResponse::new).toList();
    }

    @PostMapping
    public ResponseEntity<StockAdjustmentResponse> create(
            @Valid @RequestBody StockAdjustmentRequest request, Authentication authentication) {

        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setAdjustmentType(request.getAdjustmentType());
        adjustment.setDirection(request.getDirection());
        adjustment.setQuantity(request.getQuantity());
        adjustment.setReferenceNumber(request.getReferenceNumber());
        adjustment.setNotes(request.getNotes());

        String username = authentication == null ? "SYSTEM" : authentication.getName();

        StockAdjustment saved = stockAdjustmentService.saveAdjustment(adjustment, request.getProductId(), username);

        return ResponseEntity.status(HttpStatus.CREATED).body(new StockAdjustmentResponse(saved));
    }
}
