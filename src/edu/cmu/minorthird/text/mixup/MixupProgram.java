/* Copyright 2003, Carnegie Mellon, All Rights Reserved */

package edu.cmu.minorthird.text.mixup;

import edu.cmu.minorthird.text.*;
import edu.cmu.minorthird.util.*;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.cmu.minorthird.util.gui.*;

/** Modify a textlabeling using a series of mixup expressions.

<pre>
BNF:
STATEMENT -> provide ID
STATEMENT -> require ID [,FILE]
STATEMENT -> annotateWith FILE
STATEMENT -> defDict [+case] NAME = ID, ... , ID
STATEMENT -> defTokenProp PROP:VALUE = GEN
STATEMENT -> defSpanProp PROP:VALUE = GEN
STATEMENT -> defSpanType TYPE2 = GEN
STATEMENT -> declareSpanType TYPE
STATEMENT -> onLevel NAME
STATEMENT -> offLevel NAME
STATEMENT -> importFromLevel NAME TYPE = TYPE
  
GEN -> [TYPE]: MIXUP-EXPR
GEN -> [TYPE]- MIXUP-EXPR
GEN -> [TYPE]~ re 'REGEX',NUMBER
GEN -> [TYPE]~ trie phrase1, phrase2, ... ;

statements are semicolon-separated
// and comments look like this (C++ style)

SEMANTICS:
execute each command in order, saving spans/tokens as types, and asserting properties
'=:' can be replaced with '=TYPE:', in which case the expr will be applied to
each span of the given type, rather than all top-level spans

defDict FOO = bar,baz,bat stores a lowercase version of each word the dictionary
defDict +case FOO = blah,Bar,baZ stores each word the dictionary, preserving case

in dictionaries and tries, a double-quoted word "foo.txt" means to
find foo.txt on the classpath and store all lines from the file as
words (after trimming them).

TYPE: MIXUP-EXPR finds all spans inside a span of type TYPE that match the expression
TYPE- MIXUP-EXPR finds all spans inside a span of type TYPE that do not contain anything matching MIXUP-EXPR

</pre> <p> Mixup is matching language for modifying TextLabels.  It
can label spans with a given TYPE (the new label for that token span)
and assign properties to spans (much like labels, but 'invisible').
There is more documentation for Mixup programs in the <a
href="package-summary.html">package-level documents for Mixup.</a>
<p>
Briefly, a Mixup program will look something like this:
<pre>
require "req1"; //requires that "abc" type spans have already been labeled.  If not, the default annoator
                //for "abc" will be used.
		require "req2", "req2.mixup"; 
                //file 'def.mixup' will be run to provide "def" labels if they are not already there
                //if  "def" labels were already generated by a different annotator, they will be used and
                //and 'def.mixup' won't be called.
		provide "xyz"; //this program will annotate the text with "xyz" labels
		defDict titleWord = mr, ms, mrs, dr; 
		//defines a dictionary (with scope of this program execution called 'titleWord'
		//containing the values "mr", "ms", "mrs", "dr" 
		defDict myDictionary = "dictionary.txt"; 
		//defines a dictionary called 'myDictionary' with values taken from the file "dictionary.txt"
		defTokenProp title:true =: ... [ai(titleWord)] ... ; //finds all spans matching a work in the dictionary titleWord
		//those spans are given the property "Name" with value "true" (a string, not boolean)
		//if the span previously had "Name" property with a different value, that is replaced
		// the "..." before and after indicate that it doesn't matter what comes before or after the token
		//to be labeled.  if I said "=: [ai(titleWord)];" the document would need to be JUST a titleword.
		defTokenProp titlePunc:1 =: ... title:true [','] ... || ... title:true ['.'] ... ;
		//spans "." or "," preceeded by a title are given the property titlePunc with value "1"
		//note that the entire '... title:true [','] ...' is an expression; or operators ("||") must be
		//<em> between</em> expressions, not within them
		defSpanType fullTitle =: ...[title:true titlePunc:1?R] ...;
		//label a span as "fullTitle" if there is a title span optionally followed b a titlePunc span
		//but not more than one (from the R)
		defSpanType the =: ... [eqi('the')] ...; 
		//labels occurances of "the" ignoring case (eq = equals, adding i ignores case)
		defTokenProp aProp:t =: ...[<title:true, req1>] ...; 
		/tokens which have the title=true property AND are labeled as req1
		//are given the property aProp=t
		defTokenProp address:x =: ... [@fullTitle any] !a(myDictionary) ...; 
		//label spans of one 'fullTitle' (the @ is needed
		//before types) and the following token, whatever it is, 
		// which are followed by something other than a myDictionary word
		defTokenProp capProp:on =req2: ... [re('^[A-Z]$')] ...; 
		//on spans of type req2, match tokens fitting the given regular expression
		defSpanType listSet =: ... [address+R] ...; 
		//label as header spans of 1 or more address tokens, going all the way to 
		//right most possible token - example: blah address1 address2 address3 blah 
		// - will return three spans: "address3", "address2 address3", and "address1 address2 address3"
		defSpanType adList =: ... [L address+ R] ...; //as above but only returns the longest span
		defSpanType header =: [L address* R] ...; 
		//label longest span of 0 or more address tokens at the beginning of the document
		defSpanType shortList =: ... [address{2,3}] ...; //label spans of 2 or 3 address tokens
		defSpanType xyz =header: ...[capProp] ...; //providing the promised xyz labeling
		</pre>
		*
		* @author William Cohen
		*/

public class MixupProgram 
{
    private static Logger log = Logger.getLogger(MixupProgram.class);

    private ArrayList statementList = new ArrayList();
    // maps dictionary names to the sets they correspond to 
    private HashMap dictionaryMap = new HashMap();

    private static TextBase textBase = null;
    private static MonotonicTextLabels labels = null;
    private static HashMap textBases = new HashMap(); //List of TextBases with different tokenizations
    private static HashMap textLabels = new HashMap(); //List of TextLabels with for textBases with different tokenizations

    // current tokenization level 
    private static String currentLevel = new String("original");

    public static Set legalKeywords = new HashSet(); 
    static { 
	legalKeywords.add("defTokenProp"); 
	legalKeywords.add("defSpanProp"); 
	legalKeywords.add("defSpanType"); 
	legalKeywords.add("defDict"); 
	legalKeywords.add("declareSpanType"); 
	legalKeywords.add("provide"); 
	legalKeywords.add("require"); 
	legalKeywords.add("annotateWith"); 
	legalKeywords.add("defLevel");
	legalKeywords.add("onLevel");
	legalKeywords.add("offLevel");
	legalKeywords.add("importFromLevel");
	legalKeywords.add("//");
	legalKeywords.add("\n");
    }

    public MixupProgram() {;}

    /** Create a MixupProgram from an array of statements */
    public MixupProgram(String[] statements) throws Mixup.ParseException {
	String program = "";
	for (int i=0; i<statements.length; i++) {
	    program = program + statements[i] + ";\n";
	}
	startProgram(program);
    }

    /** Create a MixupProgram from single string with a bunch of semicolon-separated statements. */
    public MixupProgram(String program) throws Mixup.ParseException {
	String[] lines  = program.split("\n");
	StringBuffer buf = new StringBuffer();
	String line;
	for(int i=0; i<lines.length; i++) {
	    int startComment = lines[i].indexOf("//");
	    if (startComment>=0) line = lines[i].substring(0,startComment); else line = lines[i];
	    buf.append(line);
	    buf.append("\n");
	}
	program = buf.toString();
	startProgram(program);
    }

    /** Create a MixupProgram from the contents of a file. */
    public MixupProgram(File file) throws Mixup.ParseException, FileNotFoundException, IOException {
	//LineNumberReader in = new LineNumberReader(new FileReader(file));
	LineNumberReader in = file.exists() ? mixupReader(file) : mixupReader(file.getName());
	StringBuffer buf = new StringBuffer();
	String line;
	while ((line = in.readLine())!=null) {
	    int startComment = line.indexOf("//");
	    if (startComment>=0) line = line.substring(0,startComment);
	    buf.append(line);
	    buf.append("\n");
	}
	in.close();
	String program = buf.toString();
	startProgram(program);
    }

    private void startProgram(String program)throws Mixup.ParseException 
    {
	program.trim();	
	Mixup.MixupTokenizer tok = new Mixup.MixupTokenizer(program);
	String keyword = tok.advance(legalKeywords);
	while(keyword!=null) {
	    if(!keyword.startsWith("\n"))
		addStatement(tok,keyword);
	    keyword = tok.advance(legalKeywords);
	    if(keyword == null) 
		{
		    break;
		}
	}
    }

    public MonotonicTextLabels eval(MonotonicTextLabels labels, TextBase tb) {
	MultiLevelTextLabels multi = new MultiLevelTextLabels(labels);
	eval(multi);
	return multi.getLabels();
    }

    /** Evaluate the program against an existing labels. */
    public void eval(MultiLevelTextLabels labels) {
	ProgressCounter pc = new ProgressCounter("mixup program","statement",statementList.size());
	for (int i=0; i<statementList.size(); i++) {
	    ((Statement)statementList.get(i)).eval(labels);
	    pc.progress();
	    //new ViewerFrame("Labels " + currentLevel, new SmartVanillaViewer(labels));
	}
	//new ViewerFrame("Labeled TextBase", new SmartVanillaViewer(lbls));	
	pc.finished();
    }

    /** Add a single statement to the current mixup program. */
    public void addStatement(Mixup.MixupTokenizer tok, String keyword) throws Mixup.ParseException
    {
	statementList.add(new Statement(tok, keyword));
    }

    /** Add a single statement to the current mixup program. */
    public void addStatement(String statement) throws Mixup.ParseException
    {
	Mixup.MixupTokenizer tok = new Mixup.MixupTokenizer(statement);
	String keyword = tok.advance(legalKeywords);
	addStatement(tok, keyword);	
    }

    /** List the program **/
    public String toString() {
	StringBuffer buf = new StringBuffer("");
	for (int i=0; i<statementList.size(); i++) {
	    buf.append(statementList.get(i).toString()+";\n");
	}
	return buf.toString();
    }
	
    //
    // encodes a single program statement
    //
    private static class Statement {
	private static int REGEX=1, MIXUP=2, FILTER=3, PROVIDE=4, REQUIRE=5, DECLARE=6, TRIE=7, ANNOTATE_WITH=8;

	// encodes the statement properties
	private String keyword, property, type, startType, value;
	// set of words, for a dictionary
	private Set wordSet = null;
	// split string for retokenizing textBase
	private String split, patt;
	// current tokenization level 
	private String level;
	// Variables that define the level and type to be imported to the current textBase
	private String importLevel, importType, oldType;
	// encode generator
	private int statementType;
	// for statementType = MIXUP or FILTER
	private Mixup mixupExpr = null;
	// for statementType = REGEX
	private String regex = null;
	private int regexGroup;
	// for statementType = TRIE
	private Trie trie = null;
	// for statementType=PROVIDE,REQUIRE
	private String annotationType,fileToLoad;
	// for parsing
	private Matcher matcher;
	private int lastTokenStart;
	private String input;
	private static Set generatorStart = new HashSet();
	private static Set legalKeywords = new HashSet(); 
	private static Set colonEqualsOrCase = new HashSet();
	private static Set defLevelType = new HashSet();
	static { 
	    legalKeywords.add("defTokenProp"); 
	    legalKeywords.add("defSpanProp"); 
	    legalKeywords.add("defSpanType"); 
	    legalKeywords.add("defDict"); 
	    legalKeywords.add("declareSpanType"); 
	    legalKeywords.add("provide"); 
	    legalKeywords.add("require"); 
	    legalKeywords.add("defLevel");
	    legalKeywords.add("onLevel");
	    legalKeywords.add("offLevel");
	    legalKeywords.add("importFromLevel");
	}
	static { colonEqualsOrCase.add(":"); colonEqualsOrCase.add("="); colonEqualsOrCase.add("case"); }
	static { generatorStart.add(":"); generatorStart.add("~"); generatorStart.add("-"); }
	static { defLevelType.add("re"); defLevelType.add("split"); defLevelType.add("filter"); defLevelType.add("pseudotoken");}
	//
	// constructor and parser
	//
	Statement(Mixup.MixupTokenizer tok, String firstTok) throws Mixup.ParseException 
	{
	    keyword = firstTok;
	    if (keyword.equals("declareSpanType")) {
		statementType = DECLARE;
		type = tok.advance(null);
		return;
	    }
	    if (keyword.equals("provide")) {
		statementType = PROVIDE;
		annotationType = tok.advance(null);
		if (annotationType.charAt(0)=='\'') {
		    annotationType = annotationType.substring(1,annotationType.length()-1);
		}
		String marker = tok.advance(null); //Collections.singleton(","));
		return;
	    }
	    if (keyword.equals("annotateWith")) {
		statementType = ANNOTATE_WITH;
		fileToLoad = tok.advance(null);
		if (fileToLoad.charAt(0)=='\'') {
		    fileToLoad = fileToLoad.substring(1, fileToLoad.length()-1);
		}
		tok.advance(null);
		return;
	    }
	    if (keyword.equals("require")) {
		statementType = REQUIRE;
		annotationType = tok.advance(null);
		if (annotationType.charAt(0)=='\'') {
		    annotationType = annotationType.substring(1,annotationType.length()-1);
		}
		String marker = tok.advance(null); //Collections.singleton(","));
		log.debug("marker: " + marker);
		if (marker != null)  {
		    fileToLoad = tok.advance(null);
		    if (fileToLoad.charAt(0) == '\'')
			fileToLoad = fileToLoad.substring(1, fileToLoad.length() - 1);
		    tok.advance(null);
		}
		return;
	    }
	    if("onLevel".equals(keyword) || "offLevel".equals(keyword)) {
		level = tok.advance(null);		
		if("offLevel".equals(keyword))
		    level = "original";
		tok.advance(null);
		return;
	    } 
	    if("importFromLevel".equals(keyword)) {
		importLevel = tok.advance(null);
		// continue to parse NEWTYPE = OLDTYPE
	    } 
	    String propOrType = tok.advance(null);  // read property or type
	    importType = propOrType;
	    String token = tok.advance(colonEqualsOrCase); // read ':' or '='
	    if (":".equals(token)) {
		if (!"defSpanProp".equals(keyword) && !"defTokenProp".equals(keyword)) {
		    parseError("can't define properties here");
		}
		property = propOrType; type = null;
		value = tok.advance(null);
		tok.advance(Collections.singleton("="));
	    } else if ("case".equals(token)) {
		if (!"defDict".equals(keyword)) parseError("illegal keyword usage");
	    } else {
		// token is '='
		if (!"defSpanType".equals(keyword) && !"defDict".equals(keyword) && !"defLevel".equals(keyword) && !"importFromLevel".equals(keyword)) {		    
		    parseError("illegal keyword usage");
		}
		if (!"=".equals(token)) {

		    parseError("expected '='");
		}
		type = propOrType; property = null;
	    }

	    if ("defDict".equals(keyword)) {
		// syntax is "defDict [+case] dictName = ", so either
		// propOrType = dictName and token = '=', or else 
		// propOrType = + and token = 'case', or else 
		boolean ignoreCase = true;
		if ("case".equals(token)) {
		    ignoreCase = false;
		    if (!"+".equals(propOrType)) parseError("illegal defDict");
		    type = tok.advance(null);
		    tok.advance(Collections.singleton("="));
		} else {
		    type = propOrType;					
		}
		wordSet = new HashSet();
		while (true) {
		    String w =  tok.advance(null);
		    // read in each line of the file name embraced by double quotes	
		    if (w.equals("\"")) {
			StringBuffer defFile = new StringBuffer("");
			while (!(w = tok.advance(null)).equals("\""))
			    defFile.append(w);
			try {
			    LineNumberReader bReader = mixupReader(defFile.toString());
			    String s = null;
			    while ((s = bReader.readLine()) != null) {
				s = s.trim(); // remove trailing blanks
				if (ignoreCase) s = s.toLowerCase();
				wordSet.add( s );
			    }
			    bReader.close();
			} catch (IOException ioe) {
			    parseError("Error when reading " + defFile.toString() + ": " + ioe);
			}
		    } else {
			wordSet.add( ignoreCase?w.toLowerCase() : w );
		    }
		    String sep = tok.advance(null);
		    if (sep==null) break;
		    else if (!",".equals(sep)) parseError("expected comma");
		}
	    } else if("defLevel".equals(keyword)) {
		split =  tok.advance(defLevelType);
		patt = tok.advance(null);
		if(patt.charAt(0)==39 && patt.charAt(patt.length()-1)==39)
		    patt = patt.substring(1,patt.length()-1);
		tok.advance(null);
	    } else {
		// GEN
		// should be at '=' sign or starttype
		token = tok.advance(null);
		if (generatorStart.contains(token) ||"importFromLevel".equals(keyword) ) {
		    startType = "top";
		} else {
		    startType = token;
		    token = tok.advance( generatorStart );
		}
		if("importFromLevel".equals(keyword)) {
		    oldType = token;
		}else if (token.equals(":")) {
		    statementType = MIXUP;
		    //mixupExpr = new Mixup( tok.input.substring(tok.matcher.end(1),tok.input.length()) );
		    //if(tok.advance())
		    if(tok.advance())
			mixupExpr = new Mixup(tok);
		} else if (token.equals("-")) {
		    statementType = FILTER;
		    //mixupExpr = new Mixup( tok.input.substring(tok.matcher.end(1),tok.input.length()) );
		    //if(tok.advance())		    
		    if(tok.advance())
			mixupExpr = new Mixup(tok);
		} else if (token.equals("~")) {
		    token = tok.advance(null);
		    if ("re".equals(token)) {
			statementType = REGEX;
			regex = tok.advance(null);
			System.out.println("THIS IS THE REGEX: " + regex);
			if (regex.startsWith("'")) {
			    regex = regex.substring(1,regex.length()-1);
			    regex = regex.replaceAll("\\\\'","'");
			}
			token = tok.advance(Collections.singleton(","));
			token = tok.advance(null);
			System.out.println("THIS IS THE EXPECTED GROUP NUMBER: " + token);
			try {
			    regexGroup = Integer.parseInt(token);
			    token = tok.advance(null);
			} catch (NumberFormatException e) {
			    parseError("expected a regex group number and saw "+token);
			}
		    } else if ("trie".equals(token)) {
			statementType = TRIE;
			ArrayList phraseList = new ArrayList();
			String word = tok.advance(null);
			word.trim();
			String fullWord = "";
			while(word != null) {			    
			    if(!word.equals(",")) {
				fullWord = fullWord + word + " ";							
			    } else {
				fullWord.trim();
				phraseList.add(fullWord);
				fullWord = "";
			    }
			    word = tok.advance(null);
			}
			phraseList.add(fullWord);
			//String[] phrases = (String[])phraseList.toArray();
			trie = new Trie();
			BasicTextBase tokenizerBase = new BasicTextBase();
			for (int i=0; i<phraseList.size(); i++) {
			    String[] toks = tokenizerBase.splitIntoTokens((String)phraseList.get(i));
			    if (toks.length<=2 || !"\"".equals(toks[0]) || !"\"".equals(toks[toks.length-1])) {
				trie.addWords( "phrase#"+i, toks );
			    } else {
				StringBuffer defFile = new StringBuffer("");
				for (int j=1; j<toks.length-1; j++) {
				    defFile.append(toks[j]);
				}
				try {
				    //BufferedReader bReader = new BufferedReader(new FileReader(defFile.toString()));
				    LineNumberReader bReader = mixupReader(defFile.toString());
				    String s = null;
				    int line=0;
				    while ((s = bReader.readLine()) != null) {
					line++;
					String[] words = tokenizerBase.splitIntoTokens(s);
					trie.addWords(defFile+".line."+line, words);
				    }
				    bReader.close();				    
				} catch (IOException ioe) {
				    parseError("Error when reading " + defFile.toString() + ": " + ioe);
				}
			    } // file load 
			} // each phrase
		    } else {
			parseError("expected 're' or 'trie'");
		    }
		} else {
		    throw new IllegalStateException("unexpected generatorStart '"+token+"'");
		}
	    }
	}

	public void eval(MultiLevelTextLabels labels) {
	    log.info("Evaluating: "+this);
	    long start = System.currentTimeMillis();
	    if ("defDict".equals(keyword)) {
		log.debug("defining dictionary of: " + wordSet);
		labels.defineDictionary( type, wordSet );
	    } else if("defLevel".equals(keyword)) {
		labels.createLevel(type, split, patt);
	    } else if("onLevel".equals(keyword)) {
		labels.onLevel(level);		
	    } else if("offLevel".equals(keyword)) {
		labels.offLevel();
	    } else if("importFromLevel".equals(keyword)) {
		System.out.println("exec: importFromLevel "+oldType+" -> "+type);
		labels.importFromLevel(importLevel, oldType, type);		
	    } else if ("declareSpanType".equals(keyword)) {
		labels.declareType( type );
	    } else if (statementType==PROVIDE) {
		labels.setAnnotatedBy(annotationType);
	    } else if (statementType==REQUIRE) {
		labels.require(annotationType,fileToLoad);
	    } else if (statementType==ANNOTATE_WITH) {
		try {
		    //Annotator ann = (Annotator)IOUtil.loadSerialized(new File(fileToLoad));
		    InputStream s = ClassLoader.getSystemResourceAsStream(fileToLoad);
		    Annotator ann = (Annotator)IOUtil.loadSerialized(s);
		    ann.annotate( labels );
		} catch (IOException ex) {
		    throw new IllegalStateException("no serialized annotator found in '"+fileToLoad+"': error = "+ex);
		}
	    } else {
		Span.Looper input = null;
		if ("top".equals(startType)) {
		    input = labels.getTextBase().documentSpanIterator();
		} else if (labels.isType(startType)) {
		    input = labels.instanceIterator(startType);
		} else {
		    throw new IllegalStateException("no type '"+startType+"' defined");
		}
		if (statementType==MIXUP) {
		    for (Span.Looper i=mixupExpr.extract(labels,input); i.hasNext(); ) {
			Span span = i.nextSpan();
			extendLabels( labels, span );
		    }
		    // make sure type is declared, even if nothing happened to be defined here
		    if ("defSpanType".equals(keyword)) {
			labels.declareType(type);
		    }
		} else if (statementType==FILTER) {
		    TreeSet accum = new TreeSet();
		    for (Span.Looper i=input; i.hasNext(); ) {
			Span span = i.nextSpan();
			if (!hasExtraction(mixupExpr,labels,span)) {
			    accum.add( span );
			}
		    }
		    for (Iterator i=accum.iterator(); i.hasNext(); ) {
			extendLabels( labels, ((Span)i.next()) );
		    }
		} else if (statementType==TRIE) {
		    while (input.hasNext()) {
			Span span = input.nextSpan();
			Span.Looper output = trie.lookup( span );
			while (output.hasNext()) {
			    extendLabels( labels, output.nextSpan() );
			}
		    }
		} else if (statementType==REGEX) {
		    Pattern pattern = Pattern.compile(regex); 
		    while (input.hasNext()) {
			Span span = input.nextSpan();
			Matcher matcher = pattern.matcher( span.asString() );
			while (matcher.find()) {
			    try {
				Span subspan = span.charIndexProperSubSpan( matcher.start(regexGroup),matcher.end(regexGroup));
				extendLabels( labels, subspan );
			    } catch (IllegalArgumentException ex) {
				/* there is no subspan that is properly contained by the regex match,
				   so don't add anything */
			    }
			}
		    }
		} else {
		    throw new IllegalStateException("illegal statement type "+statementType);
		}
	    }
	    //new ViewerFrame("Result of ", new SmartVanillaViewer(labels));
	    long end = System.currentTimeMillis();
	    log.info("time: "+((end-start)/1000.0)+" sec");
	}


	// subroutine of eval - check if a mixup expression matches
	private boolean hasExtraction(final Mixup mixupExpr,final TextLabels labels,final Span span) {
	    Span.Looper input = new BasicSpanLooper(Collections.singleton(span));
	    Span.Looper output = mixupExpr.extract(labels,input);
	    return output.hasNext();
	}
	// subroutine of eval - label the span  
	private void extendLabels(MonotonicTextLabels labels,Span span) {
	    if ("defSpanType".equals(keyword)) labels.addToType(span,type);
	    else if ("defSpanProp".equals(keyword)) labels.setProperty(span,property,value);
	    else if ("defTokenProp".equals(keyword)) {
		for (int j=0; j<span.size(); j++) {
		    TextToken token = span.getTextToken(j);
		    if (property==null) throw new IllegalStateException("null property");
		    labels.setProperty(token,property,value);
		}
	    }
	}	

	/** convert a set to a string listing the elements */
	private String setContents(Set set) {
	    StringBuffer buf = new StringBuffer("");
	    for (Iterator i = set.iterator(); i.hasNext(); ) {
		if (buf.length()>0) buf.append(" ");
		buf.append("'"+i.next().toString()+"'");
	    }
	    return buf.toString();
	}
	// an error message
	private String parseError(String msg) throws Mixup.ParseException {
	    throw new Mixup.ParseException("statement error at char "+lastTokenStart+": "+msg+"\nin '"+input+"'");
	}
	public String toString() {
	    if ("defDict".equals(keyword) || "defLevel".equals(keyword)) {
		return keyword + " " +type + " = ... ";
	    } else if ("onLevel".equals(keyword) || "offLevel".equals(keyword)) {
		return keyword + " " + level;
	    } else if ("importFromLevel".equals(keyword)) {
		return keyword + " " + importLevel + " " + importType + " = " + oldType;
	    } else if (statementType==DECLARE) {
		return keyword + " " + type;
	    } else if (statementType==PROVIDE) {
		return keyword+" "+annotationType;
	    } else if (statementType==REQUIRE) {
		return keyword+" "+annotationType+","+fileToLoad;
	    } else if (statementType==ANNOTATE_WITH) {
		return keyword+" "+fileToLoad;
	    } else {
		String genString = "???";
		if (statementType==MIXUP) {
		    genString = ": "+mixupExpr.toString();
		} else if (statementType==FILTER) {
		    genString = "- "+mixupExpr.toString();
		} else if (statementType==REGEX) {
		    genString = "~ re '"+regex+"' ,"+regexGroup;
		} else if (statementType==TRIE) {
		    genString = "~ trie ...";
		}
		if (type!=null) {
		    return keyword+" "+type+" ="+startType+genString;
		} else {
		    return keyword+" "+property+":"+value+" ="+startType+genString;
		}
	    }
	}
    }

    /** Convert a string to an input stream, then a LineNumberReader. */
    static private LineNumberReader mixupReader(String fileName) throws IOException, FileNotFoundException
    {
	File file = new File(fileName);
	if (file.exists())
	    return mixupReader(file);
	else {
	    InputStream s;
	    s = EncapsulatingAnnotatorLoader.EncapsulatingClassLoader.getSystemResourceAsStream(fileName);
	    if (s==null) s = ClassLoader.getSystemResourceAsStream(fileName);
	    if (s==null) throw new IllegalArgumentException("No file named '"+fileName+"' found on classpath");
	    return new LineNumberReader(new BufferedReader(new InputStreamReader(s)));
	}
    }
    static private LineNumberReader mixupReader(File file) throws IOException, FileNotFoundException
    {
	return new LineNumberReader(new BufferedReader(new FileReader(file)));
    }


    /**
     * usage: programFile textFile/directory [outfile]
     * evaluates the given program file against the specified data (either a file or directory of files)
     * if an outfile is specified it outputs the types as operators to that file
     */
    public static void main(String[] args) {
	try {
	    MixupProgram program = new MixupProgram(new File(args[0]));
	    System.out.println("program:\n" + program.toString());
	    if (args.length>1) {
		MonotonicTextLabels labels = (MonotonicTextLabels)FancyLoader.loadTextLabels(args[1]);

		program.eval(labels, labels.getTextBase());

		if (args.length > 2)
		    {
			File outFile = new File(args[2]);
			new TextLabelsLoader().saveTypesAsOps(labels, outFile);
		    }
		else
		    for (Iterator i=labels.getTypes().iterator(); i.hasNext(); ) {
			String type = (String)i.next();
			System.out.println("Type "+type+":");
			for (Span.Looper j=labels.instanceIterator(type); j.hasNext(); ) {
			    Span span = j.nextSpan();
			    System.out.println( "\t'"+span.asString()+"'" );
			}
		    }
	    }
	} catch (Exception e) {
	    System.out.println("usage: programFile textFile/directory [outfile]");
	    e.printStackTrace();
	}
    }
}
