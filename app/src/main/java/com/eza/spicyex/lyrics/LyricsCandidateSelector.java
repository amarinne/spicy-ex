package com.eza.spicyex.lyrics;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Desktop-compatible, provider-neutral arbitration of lyric documents. */
public final class LyricsCandidateSelector {
    public enum SelectionMode { SMART, SYNC_TYPE, STRICT }

    public static final class Candidate {
        public final String provider;
        public final int orderIndex;
        public final LyricsDocument document;
        public final Double confidence;
        public Candidate(String provider, int orderIndex, LyricsDocument document) { this(provider, orderIndex, document, null); }
        public Candidate(String provider, int orderIndex, LyricsDocument document, Double confidence) {
            this.provider = provider == null ? "unknown" : provider; this.orderIndex = orderIndex; this.document = document; this.confidence = confidence;
        }
        public Candidate(LyricsDocument document, String provider, int orderIndex) { this(provider, orderIndex, document); }
    }

    public static final class Assessment {
        public final String provider; public final double totalScore, selectionScore, trackMatchScore, timingScore, textAgreementScore, syncDetailScore;
        public final boolean rejected; public final List<String> reasons;
        Assessment(String p, double total, double selection, double track, double timing, double text, double detail, boolean reject, List<String> why) {
            provider=p; totalScore=total; selectionScore=selection; trackMatchScore=track; timingScore=timing; textAgreementScore=text; syncDetailScore=detail; rejected=reject; reasons=why;
        }
    }
    public static final class Result { public final Candidate candidate; public final List<Assessment> assessments; Result(Candidate c,List<Assessment>a){candidate=c;assessments=a;} }

    private LyricsCandidateSelector() {}
    public static String normalizeLyricsComparisonText(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static List<String> texts(LyricsDocument d) { List<String> out=new ArrayList<>(); if(d==null||d.lines==null)return out; for(LyricsLine l:d.lines){if(l==null)continue;String s=normalizeLyricsComparisonText(l.text);if(!s.isEmpty()&&!l.interlude)out.add(s);} return out; }
    public static double textSimilarity(LyricsDocument a, LyricsDocument b) {
        String x=String.join("",texts(a)), y=String.join("",texts(b)); if(x.isEmpty()||y.isEmpty())return 0; if(x.equals(y))return 1;
        Map<String,Integer> m=new HashMap<>(), n=new HashMap<>(); for(int i=0;i<x.length()-1;i++)m.put(x.substring(i,i+2),m.getOrDefault(x.substring(i,i+2),0)+1); for(int i=0;i<y.length()-1;i++)n.put(y.substring(i,i+2),n.getOrDefault(y.substring(i,i+2),0)+1); int overlap=0,l=0,r=0; for(int v:m.values())l+=v;for(int v:n.values())r+=v;for(Map.Entry<String,Integer>e:m.entrySet())overlap+=Math.min(e.getValue(),n.getOrDefault(e.getKey(),0)); return l+r==0?0:2.0*overlap/(l+r);
    }
    private static double detail(LyricsDocument d){if(d==null)return 0; if("Syllable".equalsIgnoreCase(d.type))return 100; if("Word".equalsIgnoreCase(d.type))return 85; if("Line".equalsIgnoreCase(d.type))return 70; if("Static".equalsIgnoreCase(d.type))return 20; return 0;}
    /** Sync-level rank used by Auto ranking: syllable (3) > word (2) > line (1) > static/none (0). */
    public static int syncLevel(LyricsDocument d){if(d==null||d.type==null)return -1; if("Syllable".equalsIgnoreCase(d.type))return 3; if("Word".equalsIgnoreCase(d.type))return 2; if("Line".equalsIgnoreCase(d.type))return 1; if("Static".equalsIgnoreCase(d.type))return 0; return -1;}
    private static double timing(Candidate c,long durationMs){LyricsDocument d=c.document; if(d==null||texts(d).isEmpty())return 0; if("Static".equalsIgnoreCase(d.type))return texts(d).size()>=3?55:25; int bad=0,back=0,total=0; long prev=Long.MIN_VALUE, last=0, first=Long.MAX_VALUE; for(LyricsLine l:d.lines){if(l==null)continue; total++; if(l.startMs<0||l.endMs<l.startMs)bad++; if(l.startMs+250<prev)back++;prev=Math.max(prev,l.startMs);first=Math.min(first,l.startMs);last=Math.max(last,l.endMs); if("Syllable".equalsIgnoreCase(d.type)&&l.syllables!=null) for(SyllableSegment w:l.syllables){if(w==null)continue; total++; if(w.startMs<0||w.endMs<w.startMs)bad++;}} double s=100-90.0*bad/Math.max(1,total)-45.0*back/Math.max(1,d.lines.size()); if (bad>0 && "Syllable".equalsIgnoreCase(d.type)) s-=30; long dur=Math.max(1,durationMs); if(last-first<dur*.25)s-=35; else if(last-first<dur*.45)s-=18; if(d.lines.size()<3)s-=20; return Math.max(0,Math.min(100,s));}
    public static List<Assessment> assess(List<Candidate> candidates,long durationMs){List<Assessment> out=new ArrayList<>(); for(Candidate c:candidates){double track=c.confidence==null?65:Math.max(0,Math.min(100,c.confidence*100));double tm=timing(c,durationMs),det=detail(c.document);double agree=65;double best=0;for(Candidate p:candidates)if(p!=c)best=Math.max(best,textSimilarity(c.document,p.document));if(best>0)agree=best*100;boolean reject=track<30||tm<25||det==0||texts(c.document).isEmpty();double total=reject?0:Math.max(0,Math.min(100,track*.4+tm*.3+agree*.2+det*.1-("Static".equalsIgnoreCase(c.document.type)?15:0)));List<String> why=new ArrayList<>();why.add(track>=85?"strong track match":track<45?"weak track match":"usable track match");why.add(tm>=85?"healthy timing":tm<45?"suspicious timing":"usable timing");if(agree>=78)why.add("lyrics agree with other sources");if(reject)why.add("rejected: empty or malformed candidate");out.add(new Assessment(c.provider,total,total,track,tm,agree,det,reject,why));}return out;}
    public static List<Assessment> assessLyricsCandidates(List<Candidate> candidates,long durationMs) { return assess(candidates, durationMs); }
    public static Result select(List<Candidate> input,long durationMs,SelectionMode mode){List<Candidate> cs=new ArrayList<>(input);cs.sort(Comparator.comparingInt(c->c.orderIndex));List<Assessment>a=assess(cs,durationMs);Candidate chosen=null;if(mode==SelectionMode.STRICT){for(int i=0;i<cs.size();i++)if(!a.get(i).rejected){chosen=cs.get(i);break;}}else if(mode==SelectionMode.SYNC_TYPE){for(int i=0;i<cs.size();i++){Candidate c=cs.get(i);if(!a.get(i).rejected&&(chosen==null||detail(c.document)>detail(chosen.document)))chosen=c;}}else{double best=-1;for(int i=0;i<cs.size();i++)if(!a.get(i).rejected&&a.get(i).selectionScore>best){best=a.get(i).selectionScore;chosen=cs.get(i);}}return new Result(chosen,a);}
    public static Result selectLyricsCandidate(List<Candidate> candidates,long durationMs,SelectionMode mode) { return select(candidates,durationMs,mode); }
}
