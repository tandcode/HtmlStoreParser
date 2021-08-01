package model;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class Product {
    private long id;
    private String productName;
    private String brandName;
    private Set<Color> colors;
    private Price price;
}
