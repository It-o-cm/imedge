package com.intermarche.pos.client;

public class ImportAllClient {

    public static void main(String[] args) {
        System.out.println("Importing Stores...");
        StoreImporterClient.main(args);
        System.out.println("Importing Products...");
        ProductImporterClient.main(args);
        System.out.println("Importing Product Families...");
        ProductFamilyImporterClient.main(args);
        System.out.println("Importing Prices...");
        PriceImporterClient.main(args);
    }
}
