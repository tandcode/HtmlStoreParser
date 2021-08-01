import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.Color;
import model.Price;
import model.Product;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StoreParserApp {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Properties PROPS = new Properties();
    private static final List<InetSocketAddress> PROXIES = Collections.synchronizedList(new ArrayList<>());
    private static int requestCounter;

    public static void main(String[] args) {
        initProps();
        initProxies();
        parseHtmlProducts();
        parseApiProducts();
    }

    private static void parseHtmlProducts() {
        requestCounter = 0;
        Document doc = tryToConnect(PROPS.getProperty("html.url"), 5, false);

        Elements productTiles = doc.select("[data-test-id=ProductTile]");
        Collections.shuffle(productTiles);

        List<Product> products = Collections.synchronizedList(new ArrayList<>());

        for (Element product : productTiles) {
            Product p = parseHtmlProduct(product);
            products.add(p);
        }

        writeObjectToJsonFile(products, PROPS.getProperty("html.output.filename") + ".json");
        resultLog(products);
    }

    private static void parseApiProducts() {
        requestCounter = 0;
        System.setProperty("http.agent", PROPS.getProperty("api.http.agent")); //mocking browser

        JsonNode response = null;
        try {
            response = MAPPER.readTree(new URL(PROPS.getProperty("api.url")));
        } catch (IOException e) {
            e.printStackTrace();
        }
        requestCounter++;

        List<Product> products = Collections.synchronizedList(new ArrayList<>());
        response.get("entities").forEach(product -> {
            JsonNode attributes = product.get("attributes");
            JsonNode minPrice = product.get("priceRange").get("min");

            Set<Color> colors = Collections.synchronizedSet(new HashSet<>());
            attributes.get("colorDetail").get("values").forEach(color -> {
                colors.add(Color.builder()
                        .name(color.get("label").textValue())
                        .build());
            });
            String t = product.get("id").asText();
            products.add(
                    Product.builder()
                            .id(Long.parseLong(product.get("id").asText()))
                            .brandName(attributes.get("brand").get("values").get("label").textValue())
                            .productName(attributes.get("name").get("values").get("label").textValue())
                            .colors(colors)
                            .price(Price.builder()
                                    .currencyCode(minPrice.get("currencyCode").textValue())
                                    .value(BigDecimal.valueOf(Double.parseDouble(minPrice.get("withTax").asText()) / 100)
                                            .setScale(2, RoundingMode.CEILING))
                                    .build())
                            .build()
            );
        });

        writeObjectToJsonFile(products, PROPS.getProperty("api.output.filename") + ".json");
        resultLog(products);
    }

    private static void resultLog(List<Product> products) {
        log("Products processed: %d", products.size());
        log("Requests triggered: %d", requestCounter);
    }


    private static void initProps() {
        String path = Thread.currentThread().getContextClassLoader().getResource("").getPath();
        try (InputStream input = new FileInputStream(path + "app.properties")) {
            PROPS.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private static void initProxies() {
        String resourceName = "http_proxies.txt";
        try (Stream<String> lines = Files.lines(Paths.get(Thread.currentThread().getContextClassLoader().getResource(resourceName).toURI()))) {
            PROXIES.addAll(lines
                    .map(line -> new InetSocketAddress(line.replaceAll(":.*", ""),
                            Integer.parseInt(line.replaceAll(".*:", ""))))
                    .collect(Collectors.toList()));
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        }
    }

    public static Document tryToConnect(String url, int times, boolean useProxy) {
        Document doc = null;

        Proxy proxy = useProxy ? new Proxy(Proxy.Type.HTTP,
                PROXIES.get(ThreadLocalRandom.current().nextInt(PROXIES.size()))) :
                Proxy.NO_PROXY;

        Map<String,String> headers = Collections.synchronizedMap(new HashMap<>());
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("accept-encoding", "gzip, deflate, br");
        headers.put("accept-language", "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("origin", "https://www.aboutyou.de");
        headers.put("refer", "https://www.aboutyou.de");

        int i = 0;
        while( i < times){
            try {
                TimeUnit.SECONDS.sleep(10);
                doc = Jsoup.connect(url)
                        .userAgent(PROPS.getProperty("html.http.agent"))
                        .proxy(proxy)
                        .get();
                requestCounter++;
                if(doc.connection().response().statusCode() != 200) continue;
                break;
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
            i++;
        }
        return doc;
    }

    public static Product parseHtmlProduct(Element productTile) {
        String productUrl = productTile.absUrl("href");
        Element productDesc = tryToConnect(productUrl, 5, false)
                        .selectFirst("[data-test-id=BuyBox]");

        String fullPrice = productDesc.select("[data-test-id~=ProductPriceFormattedBasePrice|FormattedSalePrice]").text();
        String priceCurrencyCode = fullPrice.replaceAll(".*\\d*[,.]\\d* ", "");
        BigDecimal priceValue = new BigDecimal(fullPrice.replaceAll("[^\\d\\,]", "").replace(',', '.'));

        Set<Color> colors = Collections.synchronizedSet(new HashSet<>());
        colors.addAll(
                Arrays.stream(
                        productDesc.select("div[class~=.*\\bactive\\b]").stream()
                                .map(elem -> elem.selectFirst("[data-test-id=ColorVariantColorInfo]"))
                                .filter(Objects::nonNull)
                                .findFirst()
                                .get().text().split(" *\\/ *"))
                        .map(colorName ->
                            Color.builder()
                                    .name(colorName)
                                    .build())
                        .collect(Collectors.toSet()));

        return Product.builder()
                .id(Long.parseLong(productTile.attr("id")))
                .brandName(productTile.select("[data-test-id=BrandName]").text())
                .productName(productDesc.select("[data-test-id=ProductName]").text())
                .colors(colors)
                .price(
                        Price.builder()
                                .currencyCode(priceCurrencyCode)
                                .value(priceValue)
                                .build()
                )
                .build();
    }

    private static void writeObjectToJsonFile(Object object, String fileName) {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(fileName), object);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void log(String msg, Object... vals) {
        System.out.println(String.format(msg, vals));
    }
}
