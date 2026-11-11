package lk.aak.agency.service;

import lk.aak.agency.controller.api.CustomerApiController;
import lk.aak.agency.credit.SupplierCreditNoteRepository;
import lk.aak.agency.dto.api.CreditFollowUpRowResponse;
import lk.aak.agency.intake.CblEmailIntake;
import lk.aak.agency.intake.CblEmailIntakeLog;
import lk.aak.agency.model.DeliveryTrip;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.repository.DeliveryTripRepository;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.ShopReturnRepository;
import lk.aak.agency.repository.SupplierReturnRepository;
import lk.aak.agency.util.BusinessTime;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bell: everything that needs a person right now, worked out from the live records (so an item disappears by itself the moment the
 * work is done, and nothing can be forgotten or "marked read" by mistake). Every item carries the page where the work is done:
 * a single thing (one draft invoice, one trip, one CBL e-mail) opens that thing; a group of things opens the list it sits in.
 *
 * Owner and office see the whole agency; a sales rep sees only their own bills and shops; anyone else sees nothing.
 */
@Service
public class NotificationService {

    public static final String DANGER = "DANGER";
    public static final String WARNING = "WARNING";
    public static final String INFO = "INFO";

    /** How many single items one kind of notification lists before it folds the rest into one "and N more" line. */
    static final int PER_KIND = 5;
    /** How far back the hand-over and returned-cheque reminders look. */
    static final int LOOK_BACK_DAYS = 30;

    public record Notification(String id, String severity, String category, String title, String detail, String link, long count) {
    }

    private final CblEmailIntakeLog cblEmails;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final SupplierCreditNoteRepository creditNoteRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final CustomerApiController customerApiController;
    private final PaymentRepository paymentRepository;
    private final DeliveryTripRepository deliveryTripRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final ShopReturnRepository shopReturnRepository;
    private final SupplierReturnRepository supplierReturnRepository;
    private final SalesRepScopeService salesRepScopeService;

    public NotificationService(CblEmailIntakeLog cblEmails, PurchaseInvoiceRepository purchaseInvoiceRepository,
                               SupplierCreditNoteRepository creditNoteRepository, ProductRepository productRepository,
                               InventoryService inventoryService, CustomerApiController customerApiController,
                               PaymentRepository paymentRepository, DeliveryTripRepository deliveryTripRepository,
                               SalesInvoiceRepository salesInvoiceRepository, ShopReturnRepository shopReturnRepository,
                               SupplierReturnRepository supplierReturnRepository, SalesRepScopeService salesRepScopeService) {
        this.cblEmails = cblEmails;
        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.creditNoteRepository = creditNoteRepository;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.customerApiController = customerApiController;
        this.paymentRepository = paymentRepository;
        this.deliveryTripRepository = deliveryTripRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.shopReturnRepository = shopReturnRepository;
        this.supplierReturnRepository = supplierReturnRepository;
        this.salesRepScopeService = salesRepScopeService;
    }

    @Transactional(readOnly = true)
    public List<Notification> forUser(Authentication authentication) {
        boolean office = hasAuthority(authentication, "ROLE_ADMIN") || hasAuthority(authentication, "ROLE_OFFICE");
        boolean rep = !office && hasAuthority(authentication, "ROLE_SALES_REP");
        if (!office && !rep) {
            return List.of();
        }
        List<Notification> all = new ArrayList<>();
        if (office) {
            cblEmailNotifications(all);
            draftPurchaseInvoices(all);
            creditNotesWaiting(all);
            newProductsNeedPrice(all);
            stock(all);
            chequeNotifications(all);
            handovers(all);
            tripNotifications(all);
            draftBills(all, null);
            returnsWaiting(all);
            overdueCredit(all, authentication, "/customers/credit-followup");
        } else {
            draftBills(all, salesRepScopeService.resolveScopedEmployeeId(authentication));
            overdueCredit(all, authentication, "/customers");
        }
        // most urgent first; within a level, the order they were added in
        List<Notification> sorted = new ArrayList<>();
        for (String level : List.of(DANGER, WARNING, INFO)) {
            all.stream().filter(n -> level.equals(n.severity())).forEach(sorted::add);
        }
        return sorted;
    }

    // ---- CBL e-mail ------------------------------------------------------------------------------------------------

    private void cblEmailNotifications(List<Notification> out) {
        List<CblEmailIntake> needing = cblEmails.list(true, 100);
        int shown = 0;
        for (CblEmailIntake row : needing) {
            if (shown++ >= PER_KIND) {
                break;
            }
            String what = firstNonBlank(row.getDocumentNumber(), row.getFileName(), row.getEmailSubject(), "a CBL e-mail");
            String why = row.getProblems() == null ? "" : row.getProblems().lines().findFirst().orElse("");
            out.add(new Notification("cbl-email-" + row.getId(), DANGER, "CBL e-mail", "CBL e-mail could not be read",
                    clip(what + (why.isBlank() ? "" : " · " + why), 160), "/cbl-emails/" + row.getId(), 1));
        }
        if (needing.size() > PER_KIND) {
            long more = needing.size() - PER_KIND;
            out.add(new Notification("cbl-email-more", DANGER, "CBL e-mail", more + " more CBL e-mail" + (more == 1 ? "" : "s") + " could not be read",
                    "Open the list and deal with each one", "/cbl-emails?filter=attention", more));
        }
        if ("STALE".equals(cblEmails.summary().checkerStatus())) {
            out.add(new Notification("cbl-checker-stale", WARNING, "CBL e-mail", "The Gmail checker has stopped",
                    "No report from the Gmail script for over 3 hours: new CBL e-mails may be waiting", "/cbl-emails", 1));
        }
    }

    // ---- Stock coming in -------------------------------------------------------------------------------------------

    private void draftPurchaseInvoices(List<Notification> out) {
        List<PurchaseInvoice> drafts = purchaseInvoiceRepository.findByStatusOrderByIdDesc("DRAFT");
        int shown = 0;
        for (PurchaseInvoice draft : drafts) {
            if (shown++ >= PER_KIND) {
                break;
            }
            boolean fromEmail = "EMAIL".equalsIgnoreCase(draft.getSource());
            out.add(new Notification("purchase-draft-" + draft.getId(), fromEmail ? WARNING : INFO, "Stock",
                    fromEmail ? "CBL invoice arrived: count the goods and complete it" : "Purchase invoice not completed yet",
                    draft.getDocumentNumber() + " · Rs " + money(draft.getTotalAmount()), "/purchase-invoices/" + draft.getId(), 1));
        }
        if (drafts.size() > PER_KIND) {
            long more = drafts.size() - PER_KIND;
            out.add(new Notification("purchase-draft-more", INFO, "Stock", more + " more draft purchase invoice" + (more == 1 ? "" : "s"),
                    "Waiting for the goods to be counted", "/purchase-invoices", more));
        }
    }

    private void creditNotesWaiting(List<Notification> out) {
        long waiting = creditNoteRepository.countByStatus("DRAFT");
        if (waiting > 0) {
            out.add(new Notification("credit-notes-draft", WARNING, "Stock", waiting + " CBL credit note" + (waiting == 1 ? "" : "s") + " waiting for approval",
                    "Compare each with the paper from CBL, then approve", "/purchase-invoices/supplier-balance", waiting));
        }
    }

    private void newProductsNeedPrice(List<Notification> out) {
        long unpriced = productRepository.countByStatusAndStandardSellingPrice("INACTIVE", BigDecimal.ZERO);
        if (unpriced > 0) {
            out.add(new Notification("products-need-price", INFO, "Stock", unpriced + " new product" + (unpriced == 1 ? "" : "s") + " need a price",
                    "Added from a CBL invoice: inactive until you set the price", "/products", unpriced));
        }
    }

    private void stock(List<Notification> out) {
        long outOfStock = inventoryService.getOutOfStockProducts().size();
        long low = Math.max(0, inventoryService.getLowStockProducts().size() - outOfStock);
        if (outOfStock > 0) {
            out.add(new Notification("stock-out", DANGER, "Stock", outOfStock + " product" + (outOfStock == 1 ? " is" : "s are") + " out of stock",
                    "Order from CBL so bills are not refused", "/inventory", outOfStock));
        }
        if (low > 0) {
            out.add(new Notification("stock-low", WARNING, "Stock", low + " product" + (low == 1 ? " is" : "s are") + " running low",
                    "At or below the reorder level", "/inventory", low));
        }
    }

    // ---- Money -----------------------------------------------------------------------------------------------------

    private void chequeNotifications(List<Notification> out) {
        LocalDate since = BusinessTime.today().minusDays(LOOK_BACK_DAYS);
        List<Payment> returned = paymentRepository.findByPaymentMethodIgnoreCaseAndChequeStatusIgnoreCaseOrderByPaymentDateDesc("CHEQUE", "RETURNED").stream()
                .filter(p -> p.getPaymentDate() != null && !p.getPaymentDate().isBefore(since)).toList();
        if (!returned.isEmpty()) {
            out.add(new Notification("cheques-returned", DANGER, "Money", returned.size() + " cheque" + (returned.size() == 1 ? "" : "s") + " returned by the bank",
                    "Rs " + money(sum(returned)) + " · follow up with the shop", "/cheques", returned.size()));
        }
        List<Payment> toDeposit = paymentRepository.findByPaymentMethodIgnoreCaseAndChequeStatusIgnoreCaseOrderByPaymentDateDesc("CHEQUE", "RECEIVED");
        if (!toDeposit.isEmpty()) {
            out.add(new Notification("cheques-to-deposit", INFO, "Money", toDeposit.size() + " cheque" + (toDeposit.size() == 1 ? "" : "s") + " to deposit",
                    "Rs " + money(sum(toDeposit)) + " received, not yet banked", "/cheques", toDeposit.size()));
        }
        List<Payment> toClear = paymentRepository.findByPaymentMethodIgnoreCaseAndChequeStatusIgnoreCaseOrderByPaymentDateDesc("CHEQUE", "DEPOSITED");
        if (!toClear.isEmpty()) {
            out.add(new Notification("cheques-to-clear", INFO, "Money", toClear.size() + " cheque" + (toClear.size() == 1 ? "" : "s") + " waiting to clear",
                    "Rs " + money(sum(toClear)) + " banked: mark each cleared when the bank confirms", "/cheques", toClear.size()));
        }
    }

    /** Cash and cheques a collector took in the field and has not yet handed to the office, one line per collector. */
    private void handovers(List<Notification> out) {
        LocalDate since = BusinessTime.today().minusDays(LOOK_BACK_DAYS);
        Map<Long, List<Payment>> byCollector = new LinkedHashMap<>();
        for (Payment payment : paymentRepository.findByCollectedByEmployeeIdIsNotNullAndPaymentDateGreaterThanEqual(since)) {
            if (PaymentService.isHandoverItem(payment) && !"HANDED_OVER".equalsIgnoreCase(payment.getHandoverStatus())) {
                byCollector.computeIfAbsent(payment.getCollectedByEmployeeId(), id -> new ArrayList<>()).add(payment);
            }
        }
        int shown = 0;
        for (List<Payment> payments : byCollector.values()) {
            if (shown++ >= PER_KIND) {
                break;
            }
            Payment first = payments.get(0);
            LocalDate oldest = payments.stream().map(Payment::getPaymentDate).filter(d -> d != null).min(LocalDate::compareTo).orElse(null);
            out.add(new Notification("handover-" + first.getCollectedByEmployeeId(), WARNING, "Money",
                    "Collection from " + firstNonBlank(first.getCollectedByName(), "a collector") + " not handed over",
                    "Rs " + money(sum(payments)) + (oldest == null ? "" : " · since " + oldest), "/collections" + (oldest == null ? "" : "?date=" + oldest), payments.size()));
        }
        if (byCollector.size() > PER_KIND) {
            long more = byCollector.size() - PER_KIND;
            out.add(new Notification("handover-more", WARNING, "Money", more + " more collector" + (more == 1 ? "" : "s") + " have not handed over",
                    "Open Collections", "/collections", more));
        }
    }

    private void overdueCredit(List<Notification> out, Authentication authentication, String link) {
        List<CreditFollowUpRowResponse> overdue = customerApiController.creditFollowUp(authentication).getRows().stream()
                .filter(row -> row.getDaysOverdue() > 0 && row.getOverdueAmount() != null && row.getOverdueAmount().signum() > 0).toList();
        if (overdue.isEmpty()) {
            return;
        }
        BigDecimal total = overdue.stream().map(CreditFollowUpRowResponse::getOverdueAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        out.add(new Notification("credit-overdue", WARNING, "Money", overdue.size() + " shop" + (overdue.size() == 1 ? " is" : "s are") + " past the payment date",
                "Rs " + money(total) + " overdue · the oldest is " + overdue.get(0).getDaysOverdue() + " days late", link, overdue.size()));
    }

    // ---- Selling and delivering ------------------------------------------------------------------------------------

    private void tripNotifications(List<Notification> out) {
        List<DeliveryTrip> loaded = deliveryTripRepository.findByStatusOrderByTripDateAsc("LOADED");
        int shown = 0;
        for (DeliveryTrip trip : loaded) {
            if (shown++ >= PER_KIND) {
                break;
            }
            out.add(new Notification("trip-loaded-" + trip.getId(), WARNING, "Delivery", "Trip is out: record the results and close it",
                    "Trip of " + trip.getTripDate() + (trip.getVehicleNumber() == null ? "" : " · " + trip.getVehicleNumber()), "/delivery-trips/" + trip.getId(), 1));
        }
        if (loaded.size() > PER_KIND) {
            long more = loaded.size() - PER_KIND;
            out.add(new Notification("trip-loaded-more", WARNING, "Delivery", more + " more trip" + (more == 1 ? " is" : "s are") + " out", "Open Delivery trips", "/delivery-trips", more));
        }
        List<DeliveryTrip> planned = deliveryTripRepository.findByStatusOrderByTripDateAsc("PLANNED");
        shown = 0;
        for (DeliveryTrip trip : planned) {
            if (shown++ >= PER_KIND) {
                break;
            }
            out.add(new Notification("trip-planned-" + trip.getId(), INFO, "Delivery", "Trip planned: load the lorry",
                    "Trip of " + trip.getTripDate() + (trip.getVehicleNumber() == null ? "" : " · " + trip.getVehicleNumber()), "/delivery-trips/" + trip.getId(), 1));
        }
        if (planned.size() > PER_KIND) {
            long more = planned.size() - PER_KIND;
            out.add(new Notification("trip-planned-more", INFO, "Delivery", more + " more trip" + (more == 1 ? "" : "s") + " planned", "Open Delivery trips", "/delivery-trips", more));
        }
    }

    /** Bills started but not completed: the office sees all of them, a rep only the ones they wrote or their own shops'. */
    private void draftBills(List<Notification> out, Long scopedEmployeeId) {
        List<SalesInvoice> drafts = salesInvoiceRepository.findByStatusOrderByIdDesc("DRAFT").stream()
                .filter(bill -> scopedEmployeeId == null
                        || scopedEmployeeId.equals(bill.getSalesRepEmployeeId())
                        || (bill.getCustomer() != null && scopedEmployeeId.equals(bill.getCustomer().getAssignedEmployeeId())))
                .toList();
        int shown = 0;
        for (SalesInvoice bill : drafts) {
            if (shown++ >= PER_KIND) {
                break;
            }
            out.add(new Notification("bill-draft-" + bill.getId(), INFO, "Sales", "Bill not completed yet",
                    bill.getInvoiceNumber() + (bill.getCustomer() == null ? "" : " · " + bill.getCustomer().getCustomerName()), "/sales-invoices/" + bill.getId(), 1));
        }
        if (drafts.size() > PER_KIND) {
            long more = drafts.size() - PER_KIND;
            out.add(new Notification("bill-draft-more", INFO, "Sales", more + " more bill" + (more == 1 ? "" : "s") + " not completed", "Open Sales invoices", "/sales-invoices", more));
        }
    }

    private void returnsWaiting(List<Notification> out) {
        shopReturnRepository.findByStatusOrderByIdDesc("PENDING").stream().limit(PER_KIND).forEach(r ->
                out.add(new Notification("shop-return-" + r.getId(), WARNING, "Returns", "Shop return waiting for a decision",
                        firstNonBlank(r.getCustomerName(), "A shop") + " · " + r.getReturnDate(), "/shop-returns/" + r.getId(), 1)));
        supplierReturnRepository.findByStatusOrderByIdDesc("PENDING").stream().limit(PER_KIND).forEach(r ->
                out.add(new Notification("supplier-return-" + r.getId(), WARNING, "Returns", "Return to CBL not finished",
                        "Return of " + r.getReturnDate(), "/supplier-returns/" + r.getId(), 1)));
    }

    // ---- small helpers ---------------------------------------------------------------------------------------------

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private static BigDecimal sum(List<Payment> payments) {
        return payments.stream().map(p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String money(BigDecimal value) {
        BigDecimal v = (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
        return String.format(java.util.Locale.ROOT, "%,.2f", v);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
