package game.domain;

/** Explicit, serializable SplitMix64 stream. No wall clock or global randomness. */
public final class Rng {
    public long state;
    public Rng(long seed) { state=seed; }
    public long nextLong() {
        long z=(state+=0x9E3779B97F4A7C15L);
        z=(z^(z>>>30))*0xBF58476D1CE4E5B9L;
        z=(z^(z>>>27))*0x94D049BB133111EBL;
        return z^(z>>>31);
    }
    public double next() { return (nextLong()>>>11)*0x1.0p-53; }
    public int integer(int low,int high) { return low+(int)(next()*(high-low+1)); }
    public boolean chance(double probability) { return next()<probability; }
    public <T> void shuffle(java.util.List<T> list) {
        for(int i=list.size()-1;i>0;i--) java.util.Collections.swap(list,i,integer(0,i));
    }
}
