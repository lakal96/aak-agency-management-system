package lk.aak.agency.control;

import java.util.List;
import java.util.Map;

/**
 * Everything the agency has to do, every day, week and month, for Connect Plus to stay right: what the task is, who does it, which
 * Connect Plus screen it ends in, what to type there, and which checks must agree before it can be confirmed done.
 */
public final class ControlTaskDefs {

    public enum Scope { LORRY, DAY }
    public enum Freq { DAILY, WEEKLY, MONTHLY }
    /** Who it applies to: every lorry, only the van-sales lorry, or only the other lorries. */
    public enum Who { ALL, VAN, OTHERS }
    /** The form the task opens. */
    public enum Input { BILLS, CASH, TABLET_MORNING, STOCK_COUNT, ENTRIES, BOOK, CONFIRM, SIGN }

    public record Column(String field, String label, String type) { }

    public record EntryKind(String code, String title, List<Column> columns) { }

    public record Def(String code, String title, String slot, Scope scope, Freq freq, Who who, Input input, String entryKind, boolean optional,
                      String person, String screen, List<String> steps, List<String> checks, String proof) { }

    public static final String LOAD = "LOAD", UNLOAD = "UNLOAD", DAMAGED = "DAMAGED", CANCEL = "CANCEL", CHEQUE = "CHEQUE", OLD_CREDIT = "OLD_CREDIT",
            CBL_CLAIM = "CBL_CLAIM", WEEK_COUNT = "WEEK_COUNT", WEEK_CHASE = "WEEK_CHASE";

    private static final Column PRODUCT = new Column("a", "Product", "text");

    public static final Map<String, EntryKind> ENTRY_KINDS = Map.of(
            LOAD, new EntryKind(LOAD, "Load list", List.of(PRODUCT, new Column("qty", "Packs loaded", "int"))),
            UNLOAD, new EntryKind(UNLOAD, "Unload list", List.of(PRODUCT, new Column("qty", "Packs taken back", "int"))),
            DAMAGED, new EntryKind(DAMAGED, "Damaged goods", List.of(PRODUCT, new Column("qty", "Packs", "int"), new Column("b", "Reason", "text"))),
            CANCEL, new EntryKind(CANCEL, "Bills cancelled", List.of(new Column("a", "Bill no.", "text"), new Column("amount", "Bill value", "money"), new Column("b", "Reason", "text"))),
            CHEQUE, new EntryKind(CHEQUE, "Cheques received", List.of(new Column("a", "Shop", "text"), new Column("b", "Bank, cheque no., bill no.", "text"), new Column("amount", "Amount", "money"))),
            OLD_CREDIT, new EntryKind(OLD_CREDIT, "Old credit collected", List.of(new Column("a", "Shop", "text"), new Column("b", "Old bill no. and cash or cheque", "text"), new Column("amount", "Amount paid", "money"))),
            CBL_CLAIM, new EntryKind(CBL_CLAIM, "CBL claims", List.of(PRODUCT, new Column("qty", "Packs", "int"), new Column("b", "Reason", "text"), new Column("amount", "Value", "money"))),
            WEEK_COUNT, new EntryKind(WEEK_COUNT, "Products that differ", List.of(PRODUCT, new Column("qty", "Counted", "int"), new Column("b", "System showed", "text"))),
            WEEK_CHASE, new EntryKind(WEEK_CHASE, "Shops to chase", List.of(new Column("a", "Shop", "text"), new Column("amount", "Balance", "money"), new Column("b", "Promise / what happened", "text"))));

    public static final List<Def> ALL = List.of(
            // ---------------------------------------------------------------- every working day, morning
            new Def("LOAD", "Load the lorry", "MORNING", Scope.LORRY, Freq.DAILY, Who.ALL, Input.ENTRIES, LOAD, false, "Storekeeper and data entry",
                    "Stocks > Stock Load/Unload Entry (Operation = Load)",
                    List.of("Write the products and packs that go on the lorry, from the Load List. For the van write only the packs it needs on top of what is already on board.",
                            "Type the same lines into Connect Plus and press Save. For the van, save BEFORE the rep syncs the tablet.",
                            "Print the Load List (PDF button). The storekeeper and the driver (or the rep) sign it.",
                            "Tick \"done in Connect Plus\"."), List.of(), null),
            new Def("MORNING_CHECK", "Check the van's stock in the morning", "MORNING", Scope.LORRY, Freq.DAILY, Who.VAN, Input.TABLET_MORNING, null, false, "Data entry",
                    "Stocks > Stock Balance Report (vehicle AV4, today's date)",
                    List.of("Open the Stock Balance Report for the van with today's date. It must show last night's count plus this morning's load.",
                            "Ask the rep: does the tablet show the available stock?",
                            "Answer both questions below. If either is No, tell the owner before the van leaves."), List.of(), null),
            // ---------------------------------------------------------------- every working day, evening
            new Def("BILLS", "Bills and payments", "EVENING", Scope.LORRY, Freq.DAILY, Who.ALL, Input.BILLS, null, false, "Data entry",
                    "RD Sales > RD Sales Entry",
                    List.of("In Connect Plus choose the lorry and the bill date, press Get RD Sales & Payments.",
                            "Write each bill below from the lorry sheet: bill no., value, cash, cheque, credit.",
                            "Type Payment Mode, Cash Amt Paid and Cheque details for each bill in Connect Plus and Save.",
                            "Press the Excel button on RD Sales Entry and upload that file here. The check must agree before you can confirm."),
                    List.of(ControlChecks.BILL_LINES, ControlChecks.BILLS_VS_CBL, ControlChecks.NET_VS_CBL, ControlChecks.PAYMENT_VS_CBL), ControlUpload.RD_SALES),
            new Def("CASH", "Count the cash", "EVENING", Scope.LORRY, Freq.DAILY, Who.ALL, Input.CASH, null, false, "Cashier",
                    "No Connect Plus screen: the safe",
                    List.of("Count the cash note by note in front of the driver (on the van: the rep's cash and the driver's cash separately).",
                            "Write the counted amount. Never copy it from the bills."), List.of(ControlChecks.CASH_COUNT), null),
            new Def("STOCK_COUNT", "Count the van's stock", "EVENING", Scope.LORRY, Freq.DAILY, Who.VAN, Input.STOCK_COUNT, null, false, "Storekeeper and data entry",
                    "Stocks > Stock Balance Report (vehicle AV4, today's date)",
                    List.of("After the bills are saved, count what is left on the van with the rep, product by product, and write it below.",
                            "Open the Stock Balance Report for the van, press the CSV or Excel button, and upload the file here.",
                            "Write what the rep needs tomorrow in \"Load tomorrow\" (the morning load task uses it)."), List.of(ControlChecks.STOCK), ControlUpload.STOCK_BALANCE),
            new Def("UNLOAD", "Unload the lorry", "EVENING", Scope.LORRY, Freq.DAILY, Who.OTHERS, Input.ENTRIES, UNLOAD, false, "Storekeeper and data entry",
                    "Stocks > Stock Load/Unload Entry (Operation = Unload)",
                    List.of("Count back everything that returned, product by product, in front of the driver, and write it below.",
                            "Type the same into Connect Plus (Unload) and Save. Print the Unload List; the storekeeper and the driver sign it.",
                            "Open the Stock Balance Report for the lorry, press the CSV or Excel button and upload it. It must be zero."), List.of(ControlChecks.STOCK), ControlUpload.STOCK_BALANCE),
            new Def("UNLOAD_VAN", "Unload the van (only goods taken back)", "EVENING", Scope.LORRY, Freq.DAILY, Who.VAN, Input.ENTRIES, UNLOAD, true, "Storekeeper and data entry",
                    "Stocks > Stock Load/Unload Entry (Operation = Unload)",
                    List.of("Only goods really taken back to Main Stores (damaged, or goods a shop returned). Do not unload the stock that stays on the van."), List.of(), null),
            new Def("DAMAGED", "Damaged goods", "EVENING", Scope.LORRY, Freq.DAILY, Who.ALL, Input.ENTRIES, DAMAGED, true, "Storekeeper and data entry",
                    "Stocks > Damaged Stocks Entry",
                    List.of("Only if there are damaged goods today: product, packs and the reason. Type the same in Connect Plus."), List.of(), null),
            new Def("CANCELLED", "Cancelled bills", "EVENING", Scope.DAY, Freq.DAILY, Who.ALL, Input.ENTRIES, CANCEL, false, "Data entry",
                    "RD Sales > Cancel Bills (or as CBL tells you)",
                    List.of("Bills that will not be delivered or were refused: write the bill number and the reason, and cancel them in Connect Plus as CBL says.",
                            "If there are none today, leave the list empty and confirm."), List.of(), null),
            new Def("CHEQUES", "Cheques received", "EVENING", Scope.DAY, Freq.DAILY, Who.ALL, Input.ENTRIES, CHEQUE, false, "Cashier",
                    "Accounts > Cheque Deposits (when banked)",
                    List.of("Write every cheque received today: shop, bank and cheque no., bill, amount. Keep the cheques in the safe.",
                            "If there are none today, leave the list empty and confirm."), List.of(), null),
            new Def("OLD_CREDIT", "Old credit collected", "EVENING", Scope.DAY, Freq.DAILY, Who.ALL, Input.ENTRIES, OLD_CREDIT, false, "Cashier and data entry",
                    "Accounts > Outlet Bill Payments",
                    List.of("Payments shops made today on OLD bills: shop, old bill no., cash or cheque, amount.",
                            "Type each one in Connect Plus (Outlet Bill Payments). If there are none today, leave the list empty and confirm."), List.of(), null),
            new Def("SAFE", "Safe, banking and Day End", "EVENING", Scope.DAY, Freq.DAILY, Who.ALL, Input.BOOK, null, false, "Cashier and data entry",
                    "Stocks > Day End",
                    List.of("Count the safe. Write the start of the day, what was banked, what was paid out, and what is in the safe now.",
                            "Press Day End in Connect Plus only after every task above is done, then tick it here."), List.of(ControlChecks.SAFE, ControlChecks.DAY_END), null),
            // ---------------------------------------------------------------- next morning
            new Def("SIGN", "The owner checks the day and signs", "NEXT_MORNING", Scope.DAY, Freq.DAILY, Who.ALL, Input.SIGN, null, false, "Owner",
                    "The Control dashboard",
                    List.of("Open the day on the Control dashboard, read what does not agree, acknowledge each difference with a note, and sign."), List.of(), null),
            // ---------------------------------------------------------------- every week (Saturday)
            new Def("W_COUNT", "Count Main Stores", "WEEKLY", Scope.DAY, Freq.WEEKLY, Who.ALL, Input.ENTRIES, WEEK_COUNT, false, "Cashier and data entry",
                    "Stocks > Stock Balance Report (Main Stores)",
                    List.of("Count Main Stores by product BEFORE you look at the system. Someone who does not keep the store counts.",
                            "Then open the Stock Balance Report for Main Stores. Write below only the products that differ: counted and what the system showed.",
                            "Tell the owner about every difference."), List.of(), null),
            new Def("W_CHASE", "Shops to chase for payment", "WEEKLY", Scope.DAY, Freq.WEEKLY, Who.ALL, Input.ENTRIES, WEEK_CHASE, false, "Data entry",
                    "Accounts > Outlet Outstanding Report",
                    List.of("Copy the biggest and oldest balances from the Outlet Outstanding Report.", "Phone each shop and write the promise or what happened."), List.of(), null),
            new Def("W_BALANCES", "Week balances: cash, cheques, credit", "WEEKLY", Scope.DAY, Freq.WEEKLY, Who.ALL, Input.CONFIRM, null, false, "Cashier and data entry",
                    "Accounts > Cheque In Hand Report and Outlet Outstanding Report",
                    List.of("Compare the cheques in hand with the Cheque In Hand Report, and the credit owed with the Outlet Outstanding Report.",
                            "Write in the note what you compared and any difference, then confirm."), List.of(), null),
            // ---------------------------------------------------------------- every month (last working day)
            new Def("M_COUNT", "Full stock count and reconcile", "MONTHLY", Scope.DAY, Freq.MONTHLY, Who.ALL, Input.CONFIRM, null, false, "Storekeeper, cashier and data entry",
                    "Stocks > Stock Report",
                    List.of("Count everything and reconcile with Stocks > Stock Report (opening + received - sold = closing). Write the result in the note."), List.of(), null),
            new Def("M_CLAIMS", "CBL claims and credit notes", "MONTHLY", Scope.DAY, Freq.MONTHLY, Who.ALL, Input.ENTRIES, CBL_CLAIM, false, "Data entry",
                    "Claims with CBL (outside Connect Plus)",
                    List.of("Write the damaged and expired goods claimed from CBL this month and what CBL has credited so far."), List.of(), null),
            new Def("M_ALLDAYS", "Every day of the month is complete", "MONTHLY", Scope.DAY, Freq.MONTHLY, Who.ALL, Input.CONFIRM, null, false, "Data entry",
                    "The Control calendar",
                    List.of("Every day of the month must have all its tasks done. This task can only be confirmed when no day has open tasks."), List.of(), null),
            new Def("M_MONTHEND", "Month End in Connect Plus", "MONTHLY", Scope.DAY, Freq.MONTHLY, Who.ALL, Input.CONFIRM, null, false, "Owner",
                    "Maintenance > Month End",
                    List.of("Only after the owner has signed the month: press Mark Month End in Connect Plus, then confirm here."), List.of(), null));

    public static Def find(String code) {
        return ALL.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null);
    }

    private ControlTaskDefs() { }
}
