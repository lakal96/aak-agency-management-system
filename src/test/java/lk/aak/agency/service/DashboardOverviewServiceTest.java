package lk.aak.agency.service;

import lk.aak.agency.dto.api.DashboardOverviewResponse;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardOverviewServiceTest {

    @Mock private SalesInvoiceRepository invoices;
    @Mock private SalesInvoiceItemRepository items;

    private DashboardOverviewService service;

    // 12 November 2026, 01:00 in Sri Lanka = 11 November 19:30 UTC. "Today" must still be the 12th.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T19:30:00Z"), DashboardOverviewService.BUSINESS_ZONE);

    @BeforeEach
    void setUp() {
        service = new DashboardOverviewService(invoices, items, CLOCK);
        lenient().when(invoices.completedTotalsByDay(any(), any(), any())).thenReturn(List.of());
        lenient().when(invoices.buyingCustomersByDay(any(), any(), any())).thenReturn(List.of());
        lenient().when(invoices.deliveryCountsForDay(any(), any())).thenReturn(List.of());
        lenient().when(items.completedSalesByCategory(any(), any(), any())).thenReturn(List.of());
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    private static Object[] day(String date, long orders, String amount) {
        return new Object[] {LocalDate.parse(date), orders, new BigDecimal(amount)};
    }

    @Test
    void defaultsToTheCurrentMonthUpToTodayInSriLankaTime() {
        DashboardOverviewResponse response = service.overview(null, null);

        assertThat(response.month()).isEqualTo("2026-11");
        assertThat(response.rangeStart()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(response.rangeEnd()).isEqualTo(LocalDate.parse("2026-11-12"));
        assertThat(response.sales().daily()).hasSize(12);
        assertThat(response.deliveriesToday().date()).isEqualTo(LocalDate.parse("2026-11-12"));
    }

    @Test
    void comparesTheSameNumberOfDaysOfTheMonthBefore() {
        DashboardOverviewResponse response = service.overview(null, null);

        assertThat(response.comparisonStart()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(response.comparisonEnd()).isEqualTo(LocalDate.parse("2026-10-12"));
    }

    @Test
    void aFinishedMonthIsShownWholeAndComparedWithTheWholeMonthBefore() {
        DashboardOverviewResponse response = service.overview(YearMonth.of(2026, 3), null);

        assertThat(response.rangeEnd()).isEqualTo(LocalDate.parse("2026-03-31"));
        // February has 28 days, so the comparison stops at its last day rather than overflowing.
        assertThat(response.comparisonEnd()).isEqualTo(LocalDate.parse("2026-02-28"));
    }

    @Test
    void aMonthThatHasNotStartedIsRejected() {
        assertThatThrownBy(() -> service.overview(YearMonth.of(2026, 12), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalsOrdersAndPercentChange() {
        when(invoices.completedTotalsByDay(eq(LocalDate.parse("2026-11-01")), eq(LocalDate.parse("2026-11-12")), any()))
                .thenReturn(rows(day("2026-11-02", 2, "30000.00"), day("2026-11-05", 1, "10000.00")));
        when(invoices.completedTotalsByDay(eq(LocalDate.parse("2026-10-01")), eq(LocalDate.parse("2026-10-12")), any()))
                .thenReturn(rows(day("2026-10-03", 2, "20000.00")));

        DashboardOverviewResponse response = service.overview(null, null);

        assertThat(response.sales().value()).isEqualByComparingTo("40000");
        assertThat(response.sales().previous()).isEqualByComparingTo("20000");
        assertThat(response.sales().changePercent()).isEqualByComparingTo("100.0");
        assertThat(response.orders().value()).isEqualByComparingTo("3");
        assertThat(response.orders().changePercent()).isEqualByComparingTo("50.0");
        assertThat(response.sales().daily().get(1).value()).isEqualByComparingTo("30000");
        assertThat(response.sales().daily().get(0).value()).isEqualByComparingTo("0");
    }

    @Test
    void noPercentChangeWhenThereIsNothingToCompareWith() {
        when(invoices.completedTotalsByDay(eq(LocalDate.parse("2026-11-01")), any(), any()))
                .thenReturn(rows(day("2026-11-02", 1, "5000.00")));

        DashboardOverviewResponse response = service.overview(null, null);

        assertThat(response.sales().changePercent()).isNull();
    }

    @Test
    void countsEachBuyingShopOnceAndBuildsARunningTotal() {
        when(invoices.buyingCustomersByDay(eq(LocalDate.parse("2026-11-01")), eq(LocalDate.parse("2026-11-12")), any()))
                .thenReturn(rows(
                        new Object[] {LocalDate.parse("2026-11-02"), 1L},
                        new Object[] {LocalDate.parse("2026-11-02"), 2L},
                        new Object[] {LocalDate.parse("2026-11-05"), 2L},
                        new Object[] {LocalDate.parse("2026-11-05"), 3L}));

        DashboardOverviewResponse response = service.overview(null, null);

        assertThat(response.buyingCustomers().value()).isEqualByComparingTo("3");
        assertThat(response.buyingCustomers().daily().get(0).value()).isEqualByComparingTo("0");
        assertThat(response.buyingCustomers().daily().get(1).value()).isEqualByComparingTo("2");
        assertThat(response.buyingCustomers().daily().get(4).value()).isEqualByComparingTo("3");
    }

    @Test
    void keepsTheFiveBiggestCategoriesAndRollsTheRestIntoOthers() {
        List<Object[]> rows = new ArrayList<>();
        String[] names = {"A", "B", "C", "D", "E", "F", "G"};
        for (int i = 0; i < names.length; i++) {
            rows.add(new Object[] {names[i], new BigDecimal((names.length - i) * 100)});   // A=700 ... G=100
        }
        rows.add(new Object[] {"Empty", BigDecimal.ZERO});
        when(items.completedSalesByCategory(any(), any(), any())).thenReturn(rows);

        List<DashboardOverviewResponse.CategorySale> categories = service.overview(null, null).categories();

        assertThat(categories).extracting(DashboardOverviewResponse.CategorySale::category)
                .containsExactly("A", "B", "C", "D", "E", "Others");
        assertThat(categories.get(5).amount()).isEqualByComparingTo("300");          // F + G
        assertThat(categories.stream().map(DashboardOverviewResponse.CategorySale::percent)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isBetween(new BigDecimal("99.8"), new BigDecimal("100.2"));
    }

    @Test
    void sortsTodaysBillsIntoWaitingOnTheRoadDeliveredAndNeedingAttention() {
        when(invoices.deliveryCountsForDay(eq(LocalDate.parse("2026-11-12")), any())).thenReturn(rows(
                new Object[] {"PENDING", "PLANNED", 4L},
                new Object[] {"PENDING", "LOADED", 6L},
                new Object[] {"COMPLETED", "LOADED", 10L},
                new Object[] {"COMPLETED", "COMPLETED", 5L},
                new Object[] {"PARTIAL", "LOADED", 2L},
                new Object[] {"UNSUCCESSFUL", "COMPLETED", 1L},
                new Object[] {"PENDING", "COMPLETED", 3L}));

        DashboardOverviewResponse.Deliveries deliveries = service.overview(null, null).deliveriesToday();

        assertThat(deliveries.pending()).isEqualTo(4);
        assertThat(deliveries.onRoute()).isEqualTo(6);
        assertThat(deliveries.delivered()).isEqualTo(15);
        assertThat(deliveries.needAttention()).isEqualTo(6);   // partial 2 + unsuccessful 1 + closed-without-result 3
    }

    @Test
    void aSalesRepsShopsAreTheOnlyOnesQueried() {
        service.overview(null, 5L);

        verify(invoices, times(2)).completedTotalsByDay(any(), any(), eq(5L));
        verify(invoices, times(2)).buyingCustomersByDay(any(), any(), eq(5L));
        verify(invoices).deliveryCountsForDay(any(), eq(5L));
        verify(items).completedSalesByCategory(any(), any(), eq(5L));

        // ...and nothing was ever asked for the whole agency.
        verify(invoices, never()).completedTotalsByDay(any(), any(), isNull());
        verify(invoices, never()).buyingCustomersByDay(any(), any(), isNull());
        verify(invoices, never()).deliveryCountsForDay(any(), isNull());
        verify(items, never()).completedSalesByCategory(any(), any(), isNull());
    }
}
