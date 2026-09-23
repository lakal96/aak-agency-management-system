package lk.aak.agency.dto.api;

/** Shop-name-only response for the public (unauthenticated) QR scan preview - no other customer data. */
public record ShopScanPreviewResponse(String customerName) {
}
