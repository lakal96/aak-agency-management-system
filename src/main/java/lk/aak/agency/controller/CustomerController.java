package lk.aak.agency.controller;

import lk.aak.agency.model.Customer;
import lk.aak.agency.service.CustomerService;
import lk.aak.agency.service.QrCodeService;
import lk.aak.agency.service.SalesRepScopeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequestMapping("/customers")
public class CustomerController {

    private static final int PAGE_SIZE = 20;

    private final CustomerService customerService;
    private final QrCodeService qrCodeService;
    private final SalesRepScopeService salesRepScopeService;

    public CustomerController(
            CustomerService customerService,
            QrCodeService qrCodeService,
            SalesRepScopeService salesRepScopeService) {
        this.customerService = customerService;
        this.qrCodeService = qrCodeService;
        this.salesRepScopeService = salesRepScopeService;
    }

    @GetMapping
    public String showCustomerList(
            @RequestParam(defaultValue = "0") int page,
            Model model,
            Authentication authentication) {

        Page<Customer> customerPage = customerService.getCustomers(
                page, PAGE_SIZE, salesRepScopeService.resolveScopedEmployeeId(authentication));

        model.addAttribute("customers", customerPage.getContent());
        model.addAttribute("currentPage", customerPage.getNumber());
        model.addAttribute("totalPages", customerPage.getTotalPages());
        model.addAttribute("totalRecords", customerPage.getTotalElements());

        return "customers/customer-list";
    }

    @GetMapping("/new")
    public String showAddCustomerForm(Model model) {
        model.addAttribute("customer", new Customer());
        model.addAttribute("pageTitle", "Add New Customer");

        return "customers/customer-form";
    }

    @GetMapping("/edit/{id}")
    public String showEditCustomerForm(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes) {

        Customer customer = customerService
                .getCustomerById(id)
                .orElse(null);

        if (customer == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Customer not found."
            );

            return "redirect:/customers";
        }

        model.addAttribute("customer", customer);
        model.addAttribute("pageTitle", "Edit Customer");

        return "customers/customer-form";
    }

    @PostMapping("/save")
    public String saveCustomer(
            @Valid Customer customer,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            model.addAttribute(
                    "pageTitle",
                    customer.getId() == null ? "Add New Customer" : "Edit Customer"
            );

            return "customers/customer-form";
        }

        customerService.saveCustomer(customer);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Customer saved successfully."
        );

        return "redirect:/customers";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/delete/{id}")
    public String deleteCustomer(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        if (customerService.getCustomerById(id).isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Customer not found."
            );

            return "redirect:/customers";
        }

        customerService.deleteCustomer(id);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Customer deleted successfully."
        );

        return "redirect:/customers";
    }

    @GetMapping("/{id}/qr-code")
    public ResponseEntity<byte[]> getShopQrCode(@PathVariable Long id) {

        Customer customer = customerService.getCustomerById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found."));

        String scanUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/customers/scan/{qrCode}")
                .buildAndExpand(customer.getQrCode())
                .toUriString();

        byte[] pngImage = qrCodeService.generatePng(scanUrl);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(pngImage);
    }

    /** Logged-in staff go straight to the shop; anyone else only sees the shop name and a login prompt. */
    @GetMapping("/scan/{qrCode}")
    public String scanShopQrCode(
            @PathVariable String qrCode,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        Customer customer = customerService.getCustomerByQrCode(qrCode).orElse(null);

        boolean loggedIn = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        if (loggedIn) {
            if (customer == null) {
                redirectAttributes.addFlashAttribute("errorMessage", "No shop matches this QR code.");
                return "redirect:/customers";
            }

            return "redirect:/customers/" + customer.getId() + "/credit-history";
        }

        model.addAttribute("shopName", customer != null ? customer.getCustomerName() : null);
        model.addAttribute("loginUrl", "/login?redirect=/customers/scan/" + qrCode);

        return "customers/scan-landing";
    }
}