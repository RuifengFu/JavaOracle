package edu.tju.ista.llm4test.llm.memory;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ExternalAPIVectorEncoder implements VectorEncoder {
    private final String apiUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Gson gson;
    
    public ExternalAPIVectorEncoder(String apiUrl, String apiKey) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }
    
    @Override
    public List<Float> encodeText(String text) {
        try {
            Map<String, String> payload = Collections.singletonMap("text", text);
            String jsonPayload = gson.toJson(payload);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();
            
            HttpResponse<String> response = httpClient.send(
                    request, 
                    HttpResponse.BodyHandlers.ofString()
            );
            
            if (response.statusCode() == 200) {
                Map<String, Object> responseMap = gson.fromJson(
                        response.body(), 
                        Map.class
                );
                
                @SuppressWarnings("unchecked")
                List<Float> embedding = (List<Float>) responseMap.get("embedding");
                return embedding;
            } else {
                throw new RuntimeException("Failed to encode text: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to encode text", e);
        }
    }
}