java -Dquarkus.http.port=8070 \
     -Dquarkus.datasource.jdbc.url=jdbc:h2:file:./data/store-node \
     -jar target/quarkus-app/quarkus-run.jar