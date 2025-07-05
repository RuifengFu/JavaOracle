package edu.tju.ista.llm4test.utils.websearch;

import edu.tju.ista.llm4test.utils.websearch.api.ImageValue;

/**
 * 封装图片搜索结果的数据模型。
 */
public class ImageResult {
    private final String name;
    private final String contentUrl;
    private final String thumbnailUrl;
    private final String hostPageUrl;
    private final String hostPageDisplayUrl;
    private final int width;
    private final int height;
    private final String datePublished;
    
    public ImageResult(ImageValue imageValue) {
        this.name = imageValue.name;
        this.contentUrl = imageValue.contentUrl;
        this.thumbnailUrl = imageValue.thumbnailUrl;
        this.hostPageUrl = imageValue.hostPageUrl;
        this.hostPageDisplayUrl = imageValue.hostPageDisplayUrl;
        this.width = imageValue.width;
        this.height = imageValue.height;
        this.datePublished = imageValue.datePublished;
    }
    
    // Getters
    public String getName() { return name; }
    public String getContentUrl() { return contentUrl; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getHostPageUrl() { return hostPageUrl; }
    public String getHostPageDisplayUrl() { return hostPageDisplayUrl; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getDatePublished() { return datePublished; }
    
    @Override
    public String toString() {
        return String.format("ImageResult{name='%s', contentUrl='%s', size=%dx%d}", 
                           name, contentUrl, width, height);
    }
} 