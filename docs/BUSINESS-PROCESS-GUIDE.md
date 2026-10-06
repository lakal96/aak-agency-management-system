---
title: "AAK Agency Distribution Management System — Business Process Guide"
author: "AAK Agency"
date: "2026-10"
---

# AAK Agency Distribution Management System — Business Process Guide

This guide replaces the September 2026 proposal document. It was rewritten after a full,
live end-to-end walkthrough of the real system (not mockups), so every rule described here
was actually observed and verified, not assumed.

## 1. What the system does

AAK Agency distributes CBL food products to shops and customers. The system replaces paper
records and manual calculations with one connected workflow:

- Keep one list of shops (customers) and products.
- Record purchases from CBL and automatically increase warehouse stock.
- Bill shops (sales invoices, cash or credit) and automatically decrease warehouse stock.
- Enforce each shop's credit limit before a credit sale is finalized.
- Record payments against credit invoices and track UNPAID → PARTIALLY PAID → PAID.
- Manage delivery routes, vehicles and trips, shop/supplier returns, employee attendance,
  salary and advances, cheques, and audit history.
- Report on sales, purchases, payments, outstanding credit and low stock.
- Print a PDF copy of any sales or purchase invoice.

## 2. Who uses it (roles — already implemented)

| Role | Access |
|---|---|
| **ADMIN** (Owner) | Everything: every module, deleting records, managing staff logins, credit-limit overrides |
| **OFFICE** | Everything except managing user logins, viewing the audit log, and deleting records |
| **SALES_REP** | Only their own assigned shops and the invoices for those shops — enforced on every page and API endpoint, not just hidden in the menu |

Login is required for every page except the public "scan this shop's QR code" preview.

## 3. System scope

| Module | Function |
|---|---|
| Dashboard | Record counts, outstanding credit, low-stock alerts, sales trend |
| Customers | Shop identity, contact, area, credit limit/terms, assigned sales rep, QR code |
| Products | CBL code, brand, net weight, unit, MRP, selling price, reorder level |
| Purchase Invoices | Stock received from CBL — requires the original CBL invoice file before completion |
| Sales Invoices | Products supplied to shops — enforces credit limit and stock availability before completion |
| Inventory | Current stock per product, full movement history, low/out-of-stock flags |
| Payments | Cash/cheque/bank-transfer collections against credit invoices |
| Shop & Supplier Returns | Approval workflow with SALEABLE / DAMAGED / EXPIRED categories, auto stock adjustment |
| Delivery Trips, Routes, Vehicles | Assign invoices to a trip, driver/helper, delivery status |
| Employees, Attendance, Salary, Advances | HR records for drivers, sales reps, office staff |
| Cheques | Track cheque-based payments separately from cash/bank |
| Reports | Date-filtered purchases, sales, payments, outstanding credit |
| Audit Log | Who created/edited/deleted/approved which record (ADMIN only) |

## 4. Complete business process (as verified live)

1. **Create the customer** (shop). A customer code is generated automatically
   (`AAK-000001`, ...). Set a credit limit and payment terms — these are enforced later.
2. **Create the product**. One `unit` per product (e.g. `PKT`) — there is no
   box/dozen/piece conversion layer; quantities are entered directly in that unit on every
   invoice line.
3. **Create a purchase invoice** (DRAFT) and add line items, optionally with a batch
   **expiry date** per item.
4. **Upload the original CBL invoice file** (JPG/PNG/PDF) — the file's actual content is
   checked, not just its extension. **Completing a purchase invoice is blocked until this
   file is uploaded and the verification checkbox is confirmed.** This rule exists in the
   system today but was never written down before.
5. **Complete the purchase invoice.** Stock increases automatically; the invoice becomes
   immutable (can no longer be edited/deleted the normal way).
6. **Create a sales invoice** for a shop, add line items. The invoice can be saved as DRAFT
   with any quantities — stock and credit are only checked at completion time.
7. **Complete the sales invoice.** Two independent checks run, in this order:
   - **Credit limit check** (credit sales only) — blocked if `outstanding + this invoice`
     would exceed the shop's credit limit. An ADMIN can record a logged override with a
     reason; this is permanently stored on the invoice.
   - **Stock check** — blocked if any line item's quantity exceeds current stock.
   Only once both pass does stock decrease and the invoice become immutable. The invoice's
   net amount is always recalculated server-side from its line items — a client cannot
   submit a fabricated total.
8. **Cash sales** are marked PAID immediately; **credit sales** stay UNPAID until a payment
   is recorded.
9. **Record one or more payments** against a completed credit invoice. A payment greater
   than the remaining balance is rejected. Status moves UNPAID → PARTIALLY PAID → PAID
   automatically as payments are added or removed.
10. **Download a PDF** of any sales or purchase invoice at any time (new — see §7).
11. Use Inventory, Dashboard and Reports to review the current business position.

### Rules confirmed by direct testing (not just reading the code)

- A completed invoice (purchase or sales) **cannot be deleted or edited** through the
  normal screens — attempting it is blocked server-side with a clear message, even for
  ADMIN.
- Invoice numbers and purchase invoice document numbers **must be unique** — a duplicate
  is rejected with "Invoice number already exists."
- A SALES_REP cannot see, open, edit, complete, or add a payment/invoice to a shop that is
  not assigned to them, whether through the menu or by typing a direct link with someone
  else's ID.
- OFFICE can do everything ADMIN can except manage logins, read the audit log, or delete
  records.

## 5. Technology (as actually built)

| Technology | Purpose |
|---|---|
| Java 17, Spring Boot 4.1 | Backend application and REST API |
| Spring Security (session + JWT) | Thymeleaf pages use session login; the REST API (consumed by the React app) uses JWT |
| Spring Data JPA / Hibernate | Database access |
| Flyway | Versioned schema migrations (not `ddl-auto`) |
| Thymeleaf | Server-rendered pages (office/admin use, and local testing) |
| **React 19 + Vite** | The customer-facing production web app — see §6 |
| MySQL 8 | Database |
| OpenPDF | **New** — generates the printable PDF invoices (§7) |
| Docker, GitHub Actions, Terraform, AWS (EC2/ECR) | Build, deploy, and host the system |

## 6. Important correction: there are two web front ends, and the React one is the real live app

The September 2026 proposal only described the Thymeleaf pages. In reality, the production
dev server runs **both**:

- The React SPA (`aak-agency-frontend`) is the one actually reachable by users — nginx
  routes every path except `/api/**`/`/actuator/**` to it.
- The Thymeleaf pages (`aak-agency-management-system`) still exist and work, but are not
  reachable through the public nginx route on that host — they're only useful for local
  testing or if the backend is ever exposed directly.

Any new feature meant for real users must be added to the React app (or its REST API), not
just the Thymeleaf templates.

## 7. New: Printable PDF invoices

Added this cycle. Both the Thymeleaf pages and the React app now have a **Download PDF**
button on a sales invoice or purchase invoice's detail page. The PDF is generated on the
server (OpenPDF — no browser print dialog involved) and includes:

- Sales invoice PDF: customer details, line items, gross/discount/returns/net amounts, paid
  amount and remaining balance.
- Purchase invoice PDF: supplier details, line items **including each item's expiry date**,
  subtotal/discount/VAT/total.

A SALES_REP can only download the PDF for their own shops' invoices — the same ownership
check used everywhere else applies here too.

## 8. Status of previously-proposed "Future Developments"

Corrected after inspecting the real codebase — most of these are **already built**, which
the original document did not reflect:

| Item | Real status |
|---|---|
| User login and roles | ✅ Done |
| Delivery management (routes/vehicles/trips) | ✅ Done |
| Advanced credit control (limits, overrides, overdue tracking) | ✅ Done |
| Returns management (shop + supplier, approval workflow) | ✅ Done |
| Low-stock alerts | ✅ Done |
| Barcode / QR support (shop QR codes) | ✅ Done |
| Audit log | ✅ Done |
| Analytics dashboard (sales trend, reports) | ✅ Done |
| Cloud deployment | ✅ Done (AWS EC2, HTTPS, auto-deploy on push) |
| **Printable PDF documents** | ✅ **Done this cycle** (§7) |
| Expiry / batch tracking | ⚠️ Partial — expiry date per received batch exists; no separate batch *number* field yet (pending your CBL review) |
| Mobile sales app | ⚠️ Partial — responsive web app, not a native mobile app |
| Automated backup | ❌ Not yet — planned once in production (see §9) |
| Google Sheets integration | ❌ Not built — Excel import/export exists instead |

## 9. Deferred / next steps

- **Automated backups** — intentionally deferred until the system is in real production
  use, per your instruction.
- **Batch numbers alongside expiry dates** — you are reviewing this against CBL's actual
  batch/expiry data before we decide what to add.
- Everything else in the table above marked ⚠️/❌ remains a candidate for later phases.
