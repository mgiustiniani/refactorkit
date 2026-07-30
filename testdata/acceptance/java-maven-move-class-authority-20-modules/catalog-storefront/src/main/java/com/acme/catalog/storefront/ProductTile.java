package com.acme.catalog.storefront;

import com.acme.catalog.legacy.Product;

public final class ProductTile {
    public boolean supports(Object candidate) {
        return candidate instanceof Product && Product.class.isInstance(candidate);
    }
}
