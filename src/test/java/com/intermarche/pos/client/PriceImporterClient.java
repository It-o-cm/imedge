package com.intermarche.pos.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Simple HTTP client to test Price bulk import.
 * <p>
 * This class sends a CSV payload containing a list of prices to the
 * {@code /prices/import} endpoint using Java 11+ HttpClient.
 * <p>
 * Authentication is handled via Basic Auth using credentials defined in the class.
 */
public class PriceImporterClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String IMPORT_URL = BASE_URL + "/prices/import";

    // Identifiants définis dans application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    /**
     * Main method to execute import process.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: {ean, storeCode, priceExcludingTax, priceIncludingTax, vatRate, priceUsage, priority, startDateTime, endDateTime}
        // Start Date: 12 Jan 2026
        String startDate = "2026-01-12T00:00:00";

        String csvData = """
            EAN|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRIORITY|START_DATE|END_DATE
            3300000000001|1.00|1.20|0.2000|0|<<START_DATE>>|
            3300000000001|0.90|1.08|0.2000|1|<<START_DATE>>|
            3300000000002|2.50|3.00|0.2000|0|<<START_DATE>>|
            3300000000002|2.30|2.76|0.2000|1|<<START_DATE>>|
            3300000000003|0.80|0.96|0.2000|0|<<START_DATE>>|
            3300000000004|3.50|4.20|0.2000|0|<<START_DATE>>|
            3300000000005|1.20|1.44|0.2000|0|<<START_DATE>>|
            3300000000006|5.00|6.00|0.2000|0|<<START_DATE>>|
            3300000000007|0.50|0.60|0.2000|0|<<START_DATE>>|
            3300000000008|2.00|2.40|0.2000|0|<<START_DATE>>|
            3300000000009|2.50|3.00|0.2000|0|<<START_DATE>>|
            3300000000010|1.50|1.80|0.2000|0|<<START_DATE>>|
            3300000000011|1.80|2.16|0.2000|0|<<START_DATE>>|
            3300000000012|1.90|2.28|0.2000|0|<<START_DATE>>|
            3300000000013|2.00|2.40|0.2000|0|<<START_DATE>>|
            3300000000014|1.50|1.80|0.2000|0|<<START_DATE>>|
            3300000000015|1.80|2.16|0.2000|0|<<START_DATE>>|
            3300000000016|2.00|2.40|0.2000|0|<<START_DATE>>|
            3300000000017|3.00|3.60|0.2000|0|<<START_DATE>>|
            3300000000018|4.00|4.80|0.2000|0|<<START_DATE>>|
            3300000000019|3.50|4.20|0.2000|0|<<START_DATE>>|
            3300000000020|10.00|12.00|0.0550|0|<<START_DATE>>|
            3300000000020|9.50|11.40|0.0550|1|<<START_DATE>>|
            3300000000021|8.00|9.60|0.2000|0|<<START_DATE>>|
            3300000000022|2.50|3.00|0.0550|0|<<START_DATE>>|
            3300000000023|2.00|2.40|0.0550|0|<<START_DATE>>|
            3300000000024|5.00|6.00|0.2000|0|<<START_DATE>>|
            3300000000025|3.00|3.60|0.2000|0|<<START_DATE>>|
            3300000000026|2.00|2.40|0.2000|0|<<START_DATE>>|
            3300000000027|4.00|4.80|0.2000|0|<<START_DATE>>|
            3300000000028|6.00|7.20|0.2000|0|<<START_DATE>>|
            3300000000029|1.50|1.80|0.2000|0|<<START_DATE>>|
            3300000000030|2.50|3.00|0.2000|0|<<START_DATE>>|
            3300000000030|2.50|3.00|0.2000|0|<<START_DATE>>|
            3300000000031|12.00|14.40|0.2000|0|<<START_DATE>>|
            3300000000032|15.00|18.00|0.2000|0|<<START_DATE>>|
            3300000000033|25.00|30.00|0.2000|0|<<START_DATE>>|
            """;

        // Replace placeholders with actual date
        csvData = csvData.replace("<<START_DATE>>", startDate);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. Créer le header d'authentification Basic
        String auth = USERNAME + ":" + PASSWORD;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMPORT_URL))
                .header("Content-Type", "text/plain")
                // On remplace "Bearer" par "Basic"
                .header("Authorization", "Basic " + encodedAuth)
                .POST(HttpRequest.BodyPublishers.ofString(csvData))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Server Response: " + response.body());

            if (response.statusCode() == 200) {
                System.out.println("Import successful!");
            } else {
                System.err.println("Error during processing.");
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}