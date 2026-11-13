package dev.bitwigre.bml;

/** How the window orders text: names ignoring case, and versions with their numbers as numbers. */
final class TextOrder {
    private TextOrder(){}

    /** Ignoring case first; equal ignoring case, by the exact text, so the order is total. */
    static int compareHumanText(String a,String b){int cmp=String.CASE_INSENSITIVE_ORDER.compare(a,b);return cmp!=0?cmp:a.compareTo(b);}

    /** Runs of digits compare as numbers ("1.2.9" before "1.10.0"), the rest ignoring case. */
    static int compareNatural(String a,String b){
        int ia=0,ib=0;while(ia<a.length()&&ib<b.length()){
            char ca=a.charAt(ia),cb=b.charAt(ib);
            if(Character.isDigit(ca)&&Character.isDigit(cb)){
                int za=ia;while(za<a.length()&&a.charAt(za)=='0')za++;
                int zb=ib;while(zb<b.length()&&b.charAt(zb)=='0')zb++;
                int ea=za;while(ea<a.length()&&Character.isDigit(a.charAt(ea)))ea++;
                int eb=zb;while(eb<b.length()&&Character.isDigit(b.charAt(eb)))eb++;
                if(ea-za!=eb-zb)return Integer.compare(ea-za,eb-zb);
                for(int i=0;i<ea-za;i++)if(a.charAt(za+i)!=b.charAt(zb+i))return Character.compare(a.charAt(za+i),b.charAt(zb+i));
                if(ea-ia!=eb-ib)return Integer.compare(ea-ia,eb-ib);ia=ea;ib=eb;continue;
            }
            int lc=Character.compare(Character.toLowerCase(ca),Character.toLowerCase(cb));if(lc!=0)return lc;
            ia++;ib++;
        }return Integer.compare(a.length(),b.length());
    }
}
