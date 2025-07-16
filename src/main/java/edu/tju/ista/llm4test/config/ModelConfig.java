package edu.tju.ista.llm4test.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Model configuration class for JSON-based model definitions
 */
public class ModelConfig {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("displayName")
    private String displayName;
    
    @JsonProperty("apiKey")
    private String apiKey;
    
    @JsonProperty("baseUrl")
    private String baseUrl;
    
    @JsonProperty("model")
    private String model;
    
    @JsonProperty("temperature")
    private double temperature = 0.0;
    
    @JsonProperty("maxTokens")
    private int maxTokens = 8192;
    
    @JsonProperty("stream")
    private boolean stream = true;
    
    @JsonProperty("jsonOutput")
    private boolean jsonOutput = false;
    
    @JsonProperty("description")
    private String description;

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    
    public String getApiKey() { return resolveEnvVars(apiKey); }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    
    public String getBaseUrl() { return resolveEnvVars(baseUrl); }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    
    public String getModel() { return resolveEnvVars(model); }
    public void setModel(String model) { this.model = model; }
    
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    
    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
    
    public boolean isJsonOutput() { return jsonOutput; }
    public void setJsonOutput(boolean jsonOutput) { this.jsonOutput = jsonOutput; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    /**
     * Resolve environment variables in configuration values
     */
    private String resolveEnvVars(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        // Handle ${ENV_VAR} syntax
        String result = value;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^}]+)}");
        java.util.regex.Matcher matcher = pattern.matcher(value);
        
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String envValue = System.getenv(envVar);
            if (envValue != null) {
                result = result.replace("${" + envVar + "}", envValue);
            }
        }
        
        return result;
    }

    /**
     * Models configuration container
     */
    public static class ModelsConfig {
        @JsonProperty("models")
        private List<ModelConfig> models;

        public List<ModelConfig> getModels() { return models; }
        public void setModels(List<ModelConfig> models) { this.models = models; }
    }

    /**
     * Load model configurations from JSON file
     */
    public static List<ModelConfig> loadModels(String configPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ModelsConfig config = mapper.readValue(new File(configPath), ModelsConfig.class);
        return config.getModels();
    }

    /**
     * Load model configurations from default location
     */
    public static List<ModelConfig> loadDefaultModels() throws IOException {
        String configPath = "models.json";
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            // Try to find in classpath
            java.io.InputStream is = ModelConfig.class.getClassLoader().getResourceAsStream("models.json");
            if (is != null) {
                ObjectMapper mapper = new ObjectMapper();
                ModelsConfig config = mapper.readValue(is, ModelsConfig.class);
                return config.getModels();
            }
            throw new IOException("Model configuration file not found: " + configPath);
        }
        return loadModels(configPath);
    }

    /**
     * Get model by name
     */
    public static ModelConfig getModelByName(String name) throws IOException {
        return loadDefaultModels().stream()
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + name));
    }

    /**
     * Get all available model names
     */
    public static List<String> getAllModelNames() throws IOException {
        return loadDefaultModels().stream()
                .map(ModelConfig::getName)
                .collect(Collectors.toList());
    }

    /**
     * Get models as map for easy lookup
     */
    public static Map<String, ModelConfig> getModelsMap() throws IOException {
        return loadDefaultModels().stream()
                .collect(Collectors.toMap(ModelConfig::getName, m -> m));
    }
}