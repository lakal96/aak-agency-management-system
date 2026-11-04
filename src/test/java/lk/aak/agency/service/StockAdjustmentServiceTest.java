package lk.aak.agency.service;

import lk.aak.agency.model.Product;
import lk.aak.agency.model.StockAdjustment;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.StockAdjustmentRepository;
import lk.aak.agency.repository.StockMovementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockAdjustmentServiceTest {

    @Mock
    private StockAdjustmentRepository stockAdjustmentRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private RowLockService rowLockService;
    @Mock
    private AuditLogService auditLogService;

    @Test
    void saveAdjustment_openingStock_alwaysAddsToStockRegardlessOfSelectedDirection() {

        StockAdjustmentService service = new StockAdjustmentService(
                stockAdjustmentRepository, stockMovementRepository, productRepository, inventoryService, rowLockService, auditLogService
        );

        Product product = new Product();
        product.setId(1L);
        product.setUnit("PKT");

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockAdjustmentRepository.save(any(StockAdjustment.class)))
                .thenAnswer(invocation -> {
                    StockAdjustment saved = invocation.getArgument(0);
                    saved.setId(50L);
                    return saved;
                });

        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setAdjustmentType("opening_stock");
        adjustment.setDirection("OUT");
        adjustment.setQuantity(new BigDecimal("100"));
        adjustment.setNotes("Opening count at go-live");

        service.saveAdjustment(adjustment, 1L, "admin");

        assertThat(adjustment.getDirection()).isEqualTo("IN");

        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(movementCaptor.capture());

        StockMovement movement = movementCaptor.getValue();
        assertThat(movement.getMovementType()).isEqualTo("OPENING_STOCK");
        assertThat(movement.getQuantityChange()).isEqualByComparingTo("100");
    }

    @Test
    void saveAdjustment_unknownReason_isRejected() {

        StockAdjustmentService service = new StockAdjustmentService(
                stockAdjustmentRepository, stockMovementRepository, productRepository, inventoryService, rowLockService, auditLogService
        );

        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setAdjustmentType("NOT_A_REAL_REASON");
        adjustment.setQuantity(new BigDecimal("5"));

        assertThatThrownBy(() -> service.saveAdjustment(adjustment, 1L, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StockAdjustmentService service() {
        return new StockAdjustmentService(
                stockAdjustmentRepository, stockMovementRepository, productRepository, inventoryService, rowLockService
        );
    }

    private StockAdjustment adjustment(String type, String quantity, String notes) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setAdjustmentType(type);
        adjustment.setQuantity(new BigDecimal(quantity));
        adjustment.setNotes(notes);
        return adjustment;
    }

    private Product productWithId1() {
        Product product = new Product();
        product.setId(1L);
        product.setUnit("PKT");
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        return product;
    }

    @Test
    void saveAdjustment_requiresANote() {
        productWithId1();

        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service().saveAdjustment(adjustment("DAMAGED", "2", blank), 1L, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("short note");
        }
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void saveAdjustment_quantityMustBeBelowOneMillion() {
        productWithId1();

        assertThatThrownBy(() -> service().saveAdjustment(adjustment("STOCK_CORRECTION_IN", "1000000", "typo"), 1L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void saveAdjustment_openingStockIsRefusedWhenTheProductAlreadyHasMovements_afterLockingIt() {
        productWithId1();
        when(stockMovementRepository.existsByProductId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service().saveAdjustment(adjustment("OPENING_STOCK", "10", "count"), 1L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no stock movements yet");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(rowLockService, stockMovementRepository);
        order.verify(rowLockService).lockProducts(java.util.List.of(1L));
        order.verify(stockMovementRepository).existsByProductId(1L);
        verify(stockMovementRepository, never()).save(any());
    }
}
