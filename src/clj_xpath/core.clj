(ns clj-xpath.core
  (:require
   [clojure.string :as str-utils])
  (:import
   [java.io                     InputStream InputStreamReader StringReader File IOException ByteArrayInputStream]
   [org.xml.sax                 InputSource SAXException]
   [javax.xml.transform         Source]
   [javax.xml.transform.stream  StreamSource]
   [javax.xml.validation        SchemaFactory]
   [org.w3c.dom                 Document Node]
   [javax.xml.parsers           DocumentBuilderFactory]
   [javax.xml.xpath             XPathFactory XPathConstants XPathExpression]
   [javax.xml                   XMLConstants]
   [javax.xml.namespace QName])
  (:gen-class))

(def ^{:dynamic true} *namespace-aware*  false)
(def ^{:dynamic true :tag String} *default-encoding* "UTF-8")

(defn throwf
  "Helper for throwing exceptions using a format string.  Arguments are

     fmt-string
     args...

See: format"
  [& args]
  (throw (RuntimeException. ^String (apply format args))))

(defn logf
  "Debugging helper: Prints to System/err using format."
  [fmt & args]
  (.println System/err (apply format fmt args)))

(defn dom-node-map->seq
  "Convert a org.w3c.dom.NodeList into a clojure sequence."
  [^org.w3c.dom.NamedNodeMap node-list]
  (loop [length (.getLength node-list)
         idx    0
         res    []]
    (if (>= idx length)
      (reverse res)
      (recur length
             (inc idx)
             (cons (.item node-list idx) res)))))


(defn dom-node-list->seq
  "Convert a org.w3c.dom.NodeList into a clojure sequence."
  [^org.w3c.dom.NodeList node-list]
  (loop [length (.getLength node-list)
         idx    0
         res    []]
    (if (>= idx length)
      (reverse res)
      (recur length
             (inc idx)
             (cons (.item node-list idx) res)))))

(defn node-list->seq
  "Convert a org.w3c.dom.NodeList into a clojure sequence."
  [thing]
  (cond
   (isa? (class thing) org.w3c.dom.NodeList)
   (dom-node-list->seq thing)

   (isa? (class thing) org.w3c.dom.NamedNodeMap)
   (dom-node-map->seq thing)

   :unrecognized
   (throw (RuntimeException. "Unknown node list object type=%s" (class thing)))))


(def ^{:dynamic true} *validation* false)

(def disallow-doctype-decl       "http://apache.org/xml/features/disallow-doctype-decl")
(def external-general-entities   "http://xml.org/sax/features/external-general-entities")
(def external-parameter-entities "http://xml.org/sax/features/external-parameter-entities")

(defn make-dom-factory [opts]
  (doto (DocumentBuilderFactory/newInstance)
    (.setNamespaceAware *namespace-aware*)
    (.setValidating (:validation opts *validation*))
    (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING (:feature-secure-processing   opts true))
    (.setFeature disallow-doctype-decl                  (:disallow-doctype-decl       opts true))
    (.setFeature external-general-entities              (:external-general-entities   opts false))
    (.setFeature external-parameter-entities            (:external-parameter-entities opts false))))

(defn- input-stream->dom
  "Convert an input stream into a DOM."
  [^java.io.InputStream istr & [opts]]
  (let [opts        (or opts {})
        dom-factory (make-dom-factory opts)
        builder     (.newDocumentBuilder ^DocumentBuilderFactory dom-factory)
        error-h     (:error-handler opts)]
    (when error-h
      (.setErrorHandler builder error-h))
    (.parse builder istr)))

(defn xml-bytes->dom
  "Convert a byte array into a DOM."
  [bytes & [opts]]
  (with-open [istr (ByteArrayInputStream. bytes)]
    (input-stream->dom istr opts)))

(defmulti  xml->doc
  "Convert various forms of XML into a Document.  Supported forms:

    String
    byte array
    Input Stream
    org.w3c.dom.Document
    org.w3c.dom.Node
"
  (fn [thing & [opts]] (class thing)))
(defmethod xml->doc String               [thing & [opts]] (xml-bytes->dom (.getBytes ^String thing ^String *default-encoding*) opts))
(defmethod xml->doc (Class/forName "[B") [thing & [opts]] (xml-bytes->dom thing opts))
(defmethod xml->doc InputStream          [thing & [opts]] (input-stream->dom thing opts))
(defmethod xml->doc org.w3c.dom.Document [thing & [opts]] thing)
(defmethod xml->doc Node                 [thing & [opts]] thing)
(defmethod xml->doc :default             [thing & [opts]]
  (throwf "Error, don't know how to build a doc out of '%s' of class %s" thing (class thing)))

(defn attrs
  "Extract the attributes from the node."
  [^Node nodeattrs]
  (if-let [the-attrs (.getAttributes nodeattrs)]
    (loop [[^Node node & nodes] (node-list->seq (.getAttributes nodeattrs))
           res {}]
      (if node
        (recur nodes (assoc res (keyword (.getNodeName node)) (.getTextContent node)))
        res))
    nil))

(defn text
  "Accessor for text content from the node."
  [#^Node node]
  (.getTextContent node))

(defn node-name
  "Accessor for the node's name."
  [#^Node node]
  (keyword (.getNodeName node)))

(defn- node->map
  "Create a logical map out of the Node's properties:
    :node      the-node
    :tag       tag name of node
    :attrs     map of the node's attributes
    :text      the text of the node
    :children  a lazy sequence of the node's children.
"
  [#^Node node]
  (let [lazy-children (fn [#^Node n] (delay
                                      (map node->map
                                           (node-list->seq (.getChildNodes n)))))
        m  {:node node
            :tag   (node-name node)
            :attrs (attrs node)
            :text  (text node)}
        m   (if (.hasChildNodes node)
              (assoc m :children (lazy-children node))
              m)
        ]
    m))

(defmulti xp:compile
  "Compile an XPath expression.  If the argument is already a compiled XPath expression, it is returned as-is."
  class)

(def ^String foo "stuff")

(def ^{:dynamic true :tag javax.xml.xpath.XPathFactory} *xpath-factory* (org.apache.xpath.jaxp.XPathFactoryImpl.))

(def ^{:dynamic true :tag javax.xml.xpath.XPath} *xpath-compiler* (.newXPath *xpath-factory*))

(defmethod xp:compile String          [xpexpr] (.compile *xpath-compiler* xpexpr))
(defmethod xp:compile XPathExpression [xpexpr] xpexpr)
(defmethod xp:compile :default        [xpexpr]
  (throwf "xp:compile: don't know how to compile xpath expr of type:%s '%s'" (class xpexpr) xpexpr))

(defmulti $x
  "Perform an xpath query on the given XML document which may be a String, byte array, or InputStream.
See xml->doc, and xp:compile."
  (fn [xp xml-thing & [ops]] (class xml-thing)))

(defmethod $x String [xp ^String xml & [ops]]
  ($x xp (xml->doc (.getBytes xml *default-encoding*) ops)))

(defmethod $x (Class/forName "[B") [xp bytes & [ops]]
  ($x xp (xml->doc bytes ops)))

(defmethod $x InputStream [xp istr & [ops]]
  ($x xp (xml->doc istr ops)))

(defmethod $x java.util.Map [xp xml] ($x xp (:node xml)))

;; assume a Document (or api compatible)
(defmethod $x :default [xp-expression ^Document doc]
  (map node->map
       (node-list->seq
        (.evaluate ^javax.xml.xpath.XPathExpression (xp:compile xp-expression) doc XPathConstants/NODESET))))

(defn summarize
  "Summarize a string to a specific maximu length (truncating it and adding ... if it is longer than len)."
  [s len]
  (let [s (str s)]
    (if (>= len (.length s))
      s
      (str (.substring s 0 len) "..."))))

(defn $x:tag*
  "Perform an xpath search, resulting in zero or more nodes, return just the tag name."
  [xp xml & [ops]]
  (map :tag ($x xp xml ops)))

(defn $x:tag?
  "Perform an xpath search, resulting in zero or one node.  Return only the tag name."
  [xp xml & [ops]]
  (let [res ($x:tag* xp xml ops)]
    (if (next res)
      (throwf "Error, more than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    (first res)))

(defn $x:tag+
  "Perform an xpath search, resulting in one or more nodes.  Return only the tag name."
  [xp xml & [ops]]
  (let [res ($x:tag* xp xml ops)]
    (if (< (count res) 1)
      (throwf "Error, less than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    res))

(defn $x:tag [xp xml & [ops]]
  "Perform an xpath search, resulting in one and only one node.  Return only the tag name."
  (let [res ($x:tag* xp xml ops)]
    (if (not (= 1 (count res)))
      (throwf "Error, more (or less) than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    (first res)))

(defn $x:text*
  "Perform an xpath search, resulting in zero or more nodes.  Return only each the node's text."
  [xp xml & [ops]]
  (map :text ($x xp xml ops)))

(defn $x:text?
  "Perform an xpath search, resulting in zero or one node.  Return only the node's text."
  [xp xml & [ops]]
  (let [res ($x:text* xp xml ops)]
    (if (next res)
      (throwf "Error, more than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    (first res)))

(defn $x:text+
  "Perform an xpath search, resulting in one or more nodes.  Return only each the node's text."
  [xp xml & [ops]]
  (let [res ($x:text* xp xml ops)]
    (if (< (count res) 1)
      (throwf "Error, less than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    res))

(defn $x:text
  "Perform an xpath search, resulting in one and only one node.  Return only the node's text."
  [xp xml & [ops]]
  (let [res ($x:text* xp xml & [ops])]
    (if (not (= 1 (count res)))
      (throwf "Error, more (or less) than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    (first res)))

(defn $x:attrs*
  "Perform an xpath search, resulting in zero or more nodes.  When an attr-name is passed, return only each the node's attrs."
  ([xp xml]
   (map attrs (map :node ($x xp xml))))
  ([xp xml ops]
   (map attrs (map :node ($x xp xml ops))))
  ([xp xml attr-name]
   (map (if (keyword? attr-name) attr-name (keyword attr-name)) (map attrs (map :node ($x xp xml)))))
  ([xp xml attr-name ops]
   (map (if (keyword? attr-name) attr-name (keyword attr-name)) (map attrs (map :node ($x xp xml ops))))))

(defn $x:attrs?
  "Perform an xpath search, resulting in zero or one node.  Return only the node's attrs."
  [xp xml & [ops]]
  (let [res (map attrs (map :node ($x xp xml ops)))]
    (if (next res)
      (throwf "Error, more than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    (first res)))

(defn $x:attrs+
  "Perform an xpath search, resulting in one or more nodes.  Return only each the node's attrs."
  [xp xml & [ops]]
  (let [res (map attrs (map :node ($x xp xml ops)))]
    (if (< (count res) 1)
      (throwf "Error, less than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    res))


(defn $x:attrs
  "Perform an xpath search, resulting in one and only one node.  Return only the node's attrs."
  [xp xml & [ops]]
  (let [res (map attrs (map :node ($x xp xml ops)))]
    (if (not (= 1 (count res)))
      (throwf "Error, more (or less) than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    (first res)))

(defn $x:node*
  "Perform an xpath search, resulting in zero or more nodes.  Returns the nodes."
  [xp xml & [ops]]
  (map :node ($x xp xml ops)))

(defn $x:node?
  "Perform an xpath search, resulting in zero or one node.  Returns the node."
  [xp xml & [ops]]
  (let [res ($x:node* xp xml ops)]
    (if (next res)
      (throwf "Error, more than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    (first res)))

(defn $x:node+
  "Perform an xpath search, resulting in one or more nodes.  Returns the nodes."
  [xp xml & [ops]]
  (let [res ($x:node* xp xml ops)]
    (if (< (count res) 1)
      (throwf "Error, less than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    res))

(defn $x:node
  "Perform an xpath search, resulting in one and only one node.  Returns the node."
  [xp xml & [ops]]
  (let [res ($x:node* xp xml ops)]
    (if (not (= 1 (count res)))
      (throwf "Error, more (or less) than 1 result (%d) from xml(%s) for xpath(%s)"
              (count res)
              (summarize xml 10)
              xp))
    (first res)))


(defmulti format-tag
  "Helper for generating XML (mostly used by the test suite)."
  (fn [arg & [with-attrs]] (class arg)))

(defn format-tag-seq
  "Helper for generating XML (mostly used by the test suite)."
  [tag-and-attrs & [with-attrs]]
  (if with-attrs
    (let [[tag & attrs] tag-and-attrs]
      (format "%s %s" (name tag)
              (str-utils/join " " (map (fn [[key val]]
                                         (format "%s=\"%s\"" (if (keyword? key) (name key) key) val))
                                       (partition 2 attrs)))))
    (name (first tag-and-attrs))))

(defmethod format-tag clojure.lang.PersistentVector [tag-and-attrs & [with-attrs]]
  (format-tag-seq tag-and-attrs with-attrs))

(defmethod format-tag clojure.lang.LazilyPersistentVector [tag-and-attrs & [with-attrs]]
  (format-tag-seq tag-and-attrs with-attrs))

(defmethod format-tag clojure.lang.Keyword [tag & [_]]
  (name tag))

(defmethod format-tag :default [tag & [_]]
  (str tag))


(defn tag
  "Helper for generating XML (mostly used by the test suite)."
  [tag & body]
  (format "<%s>%s</%s>" (format-tag tag :with-attrs) (apply str body) (format-tag tag)))


(defn xmlnsmap-from-node
  "Extract a map of XML Namespace prefix to URI (and URI to prefix) from the given node."
  [node]
  (let [attributes (attrs node)]
    (reduce
     (fn extract-namespaces [m k]
       (if (.startsWith (name k) "xmlns")
         (let [val (get attributes k)
               k    (.replaceAll (name k) "^xmlns:?" "")]
           (-> m
               (assoc k   val)
               (assoc val k)))
         m))
     {}
     (keys attributes))))

(defn xmlnsmap-from-root-node
  "Extract a map of XML Namespace prefix to URI (and URI to prefix) from the root node of the document."
  [xml & [ops]]
  (xmlnsmap-from-node (first ($x:node* "//*" xml ops))))

(defn xmlnsmap-from-document
  "Extract a map of XML Namespace prefix to URI (and URI to prefix) recursively from the entire document."
  [xml & [ops]]
  (let [^Node node   (xml->doc xml ops)]
    (reduce
     (fn merge-nsmaps [m node]
       (merge
        m
        (xmlnsmap-from-document node)))
     (xmlnsmap-from-node node)
     (node-list->seq (.getChildNodes node)))))

(defn nscontext
  "Create a javax.xml.namespace.NamespaceContext from the given map."
  [prefix-map]
  (let [uri-map (reduce (fn unmap [m k]
                          (assoc m (get prefix-map k) k))
                        {}
                        (keys prefix-map))]
    (proxy [javax.xml.namespace.NamespaceContext]
        []
      (getNamespaceURI [prefix]
        ;;(println (format "getNamespaceURI: %s => %s" prefix (get prefix-map prefix)))
        (get prefix-map prefix))
      (getPrefixes [val]
        ;;(println (format "getPrefixes: %s" val))
        nil)
      (getPrefix [uri]
        ;;(println (format "getPrefix: %s => %s" uri (get uri-map uri)))
        (get uri-map uri)))))


(defn with-namespace-awareness*
  "Wrap the call to f with a binding setting *namespace-aware* to true and a XPath factory constructed with namespace awareness."
  [f]
  (binding [*namespace-aware* true
            *xpath-compiler*  (.newXPath *xpath-factory*)]
    (f)))

(defmacro with-namespace-awareness
  "Helper macro for with-namespace-awareness*."
  [& body]
  `(with-namespace-awareness* (fn [] ~@body)))

(defn set-namespace-context!
  "Set the namespace context on the XPath Compiler in scope.  See with-namespace-awareness* and with-namespace-awareness."
  [context-map]
  (.setNamespaceContext *xpath-compiler* (nscontext context-map)))

(defn with-namespace-context*
  "Call f in a context with namespace awareness enabled, and the given context map."
  [context-map f]
  (binding [*namespace-aware* true
            *xpath-compiler*  (.newXPath *xpath-factory*)]
    (.setNamespaceContext *xpath-compiler* (nscontext context-map))
    (f)))

(defmacro with-namespace-context
  "Helper macro for with-namespace-context."
  [context-map & body]
  `(with-namespace-context* ~context-map (fn [] ~@body)))

(defmulti abs-path*
  "Determine the absolute path to node."
  (fn [^Node node] (.getNodeType node)))

(defn- walk-back
  "Walk up the document to the root node, tracing the path all the way back up."
  [^Node node tail]
  (if-let [anc (.getParentNode node)]
    (str (abs-path* anc) "/" tail)
    tail))

(defmethod abs-path* Node/ELEMENT_NODE [^org.w3c.dom.Element node]
  (let [name (.getTagName node)
        posn (count (->> node
                         (iterate #(.getPreviousSibling ^org.w3c.dom.Element %))
                         (take-while boolean)
                         ;; (filter #(and (= Node/ELEMENT_NODE (.getNodeType ^Node %)) (= name (.getTagName ^Node %))))
                         (filter (fn [^Node node]
                                   (and (= Node/ELEMENT_NODE (.getNodeType node))
                                        (= name (.getTagName ^org.w3c.dom.Element node)))))
                         ))
        step (str name "[" posn "]")]
    (walk-back node step)))

(defmethod abs-path* Node/ATTRIBUTE_NODE [node]
  (throw (RuntimeException. "Not implemented yet.")))

(defn- node-type->xpath-function [nt]
  ({Node/TEXT_NODE                   "text"
    Node/COMMENT_NODE                "comment"
    Node/PROCESSING_INSTRUCTION_NODE "processing-instruction"} nt))

(defmethod abs-path* :default [^Node node]
  (let [nt   (.getNodeType node)
        posn (count (->> node
                         (iterate #(.getPreviousSibling ^Node %))
                         (take-while boolean)
                         (filter #(and % (= nt (.getNodeType ^Node %))))))
        step (str (node-type->xpath-function nt) "()[" posn "]")]
    (walk-back node step)))

(defmethod abs-path* Node/DOCUMENT_NODE [node] "")

(defn abs-path
  "Determine an absolute xpath expression that locates this node inside the enclosing document.
   Based on code developed by Florian Bösch on XML-SIG (http://mail.python.org/pipermail/xml-sig/2004-August/010423.html)
   as enhanced and published by Uche Ogbuji (http://www.xml.com/pub/a/2004/11/24/py-xml.html)"
  [node]
  (when (:node node) (abs-path* (:node node))))

(defn node->xml
  "Convert a Node to a String of XML."
  [^Node node]
  (let [dw         (java.io.StringWriter.)
        serializer (..
                    (javax.xml.transform.TransformerFactory/newInstance)
                    newTransformer)]
    (.transform
     serializer
     (javax.xml.transform.dom.DOMSource. node)
     (javax.xml.transform.stream.StreamResult. dw))
    (str dw)))

(defn ->qualified-name [qname]
  (cond
   (isa? (class qname) QName)
   qname

   (string? qname)
   (QName. qname)

   (and (sequential? qname) (= 1 (count qname)))
   (QName. (first qname))

   (and (sequential? qname) (= 2 (count qname)))
   (QName. (first qname) (second qname))

   (and (sequential? qname) (= 3 (count qname)))
   (QName. (nth qname 0) (nth qname 1) (nth qname 2))

   :otherwise
   (throw (RuntimeException. (format "Error: don't know how to make a QName out of: %s" qname)))))

(defonce xpath-functions (atom {}))

(def xpath-function-resolver
  (reify
    javax.xml.xpath.XPathFunctionResolver
    (resolveFunction [this fname arity]
      (get
       (->>
        @xpath-functions
        (filter
         (fn [[qname arities]]
           (= qname fname)))
        first
        second)
       arity))))

(defn register-xpath-function [qualified-name arity f]
  (let [qname (->qualified-name qualified-name)
        xpfn  (reify javax.xml.xpath.XPathFunction
                (evaluate [this args]
                  (f this args)))]
    (swap! xpath-functions
           assoc-in
           [qname arity]
           xpfn)
    (when (nil? (.getXPathFunctionResolver *xpath-compiler*))
      (.setXPathFunctionResolver *xpath-compiler* xpath-function-resolver))
    qname))
