package lk.aak.agency.controller.api;

import lk.aak.agency.batch.StockBatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only: what each purchase batch cost and the price (+10%) it implies. Owner and office only (it shows costs). */
@RestController
@RequestMapping("/api/v1/stock-batches")
public class StockBatchApiController {

    private final StockBatchService service;

    public StockBatchApiController(StockBatchService service) {
        this.service = service;
    }

    @GetMapping
    public StockBatchService.View batches(@RequestParam(defaultValue = "true") boolean onlyInStock,
                                          @RequestParam(required = false) String search) {
        return service.view(onlyInStock, search);
    }
}
