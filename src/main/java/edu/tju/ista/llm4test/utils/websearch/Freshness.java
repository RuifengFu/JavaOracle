package edu.tju.ista.llm4test.utils.websearch;

/**
 * 枚举，定义了搜索结果的时间范围。
 */
public enum Freshness {
    NO_LIMIT("noLimit"),
    ONE_DAY("oneDay"),
    ONE_WEEK("oneWeek"),
    ONE_MONTH("oneMonth"),
    ONE_YEAR("oneYear");
    
    private final String value;
    
    Freshness(String value) {
        this.value = value;
    }
    
    public String getValue() { 
        return value; 
    }
} 