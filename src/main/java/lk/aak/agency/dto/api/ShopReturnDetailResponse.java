package lk.aak.agency.dto.api;

import java.util.List;

public class ShopReturnDetailResponse {

    private final ShopReturnResponse shopReturn;
    private final List<ShopReturnItemResponse> items;

    public ShopReturnDetailResponse(ShopReturnResponse shopReturn, List<ShopReturnItemResponse> items) {
        this.shopReturn = shopReturn;
        this.items = items;
    }

    public ShopReturnResponse getShopReturn() {
        return shopReturn;
    }

    public List<ShopReturnItemResponse> getItems() {
        return items;
    }
}
