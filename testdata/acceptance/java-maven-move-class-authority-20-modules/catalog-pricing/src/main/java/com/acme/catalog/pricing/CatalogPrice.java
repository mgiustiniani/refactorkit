package com.acme.catalog.pricing;

import com.acme.catalog.legacy.Product;
import com.acme.fixture.external.PriceAuthority;

@PriceAuthority
public final class CatalogPrice {
    private final Product product = new Product();

    public Product product() {
        return product;
    }
}
