package io.github.headmaster218.recorder.core;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
/** Exact adjacent interval union for one destination revision and capture. No delivery inference. */
public final class RetirementRanges {
    public static final class Range {
        public final long low,high;
        public Range(long low,long high)throws IOException {if(low<0 || high<low || high==Long.MAX_VALUE)throw new IOException("invalid retired interval");this.low=low;this.high=high;}
    }
    private RetirementRanges() { }
    public static List<Range> add(List<Range> existing,long ordinal)throws IOException {
        if(ordinal<0 || ordinal==Long.MAX_VALUE || existing.size()>2048)throw new IOException("retirement bounds");
        List<Range> result=new ArrayList<Range>();long low=ordinal,high=ordinal,previous=-2;
        boolean inserted=false;
        for(Range range:existing) {
            if(range.low<=previous+1)throw new IOException("overlapping/noncanonical intervals");previous=range.high;
            if(range.high+1<low)result.add(range);
            else if(high+1<range.low) {if(!inserted){result.add(new Range(low,high));inserted=true;}result.add(range);}
            else {if(inserted)throw new IOException("unordered intervals");low=Math.min(low,range.low);high=Math.max(high,range.high);}
        }
        if(!inserted)result.add(new Range(low,high));return result;
    }
}
