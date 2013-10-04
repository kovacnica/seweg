(in-ns 'seweg.protocols.snmp)

;; Variable bindings cast
(defn vb2str [variable-bindings & options]
  (assert (every? #(= :sequence %) (map :type variable-bindings)) "There is something wrong with input parameter. Not every variable binding is a :sequence type.")
  (letfn [(hf [x] (cond 
                    (= :IpAddress (:type x)) (apply str (interpose "." (:value x)))
                    (= :Timeticks (:type x)) (.toString (Date. (long (:value x))))
                    (or (instance? BigInteger (:value x)) (instance? clojure.lang.BigInt (:value x))) (.toString (:value x))
                    (every? string? (:value x)) (apply str  (interpose "."  (map #(apply str %) (partition 2 (:value x)))))
                    :else (apply str (:value x))))]
    (reduce conj (for [x (map #(:value %) variable-bindings)] (hash-map (:value (first x)) (hf (second x)))))))

(defn vb2data [variable-bindings]
  (assert (every? #(= :sequence %) (map :type variable-bindings)) "There is something wrong with input parameter. Not every variable binding is a SNMP :sequence type.")
  (letfn [(hf [x] (cond 
                    (= :IpAddress (:type x)) (apply str (interpose "." (:value x)))
                    (= :Timeticks (:type x)) (:value x) ;;(Date. (long (:value x)))
                    (or (instance? BigInteger (:value x)) (instance? clojure.lang.BigInt (:value x))) (:value x)
                    (every? string? (:value x)) (apply str  (interpose "."  (map #(apply str %) (partition 2 (:value x)))))
                    (= :noSuchInstance (:type x)) :noSuchInstance
                    :else (:value x)))]
    (for [x (map #(:value %) variable-bindings)] {(:value (first x)) (hf (second x))})))

;; Function is ment to be used in repl for easier development
(defn show-variable-bindings [response]
  (doseq  [x  (-> response :message decompose-snmp-response :pdu :variable-bindings vb2str sort)]
    (let [o (split-oid (key x))
          v (val x)]
      (println (apply str "OID " (find-oid (first o)) ":" (oid2str (second o)) " = " v)))))


(defn resolve-oids-fn [x]
  (let [ot (split-oid (-> x keys first))
        o (keyword (apply str (interpose "." (conj  (map str (second ot)) (-> (find-oid (first ot)) name)))))
        v (-> x vals first)]
    (hash-map o v)))

;; Function resolves OID value if input is keyword
(defn resolve-oids [variable-bindings]
  (map resolve-oids-fn variable-bindings))

(defn get-variable-bindings [response]
  (-> response :message decompose-snmp-response :pdu :variable-bindings vb2data))

(defn get-rid [response]
  (-> response :message decompose-snmp-response :pdu :rid))





;; Following are functions for easier request interchange

(def rid-range [10000 500000])
(defn generate-request-id [] (+ (first rid-range) (rand-int (- (second rid-range) (first rid-range)))))
(defn get-new-rid [] (generate-request-id))



(defn open-line
  "Function returns a function that will genarate
  snmp requests based on community, host and request type.
  Only OID value can vary.
  
  Options are:
  :pdu-type [:get-bulk-request :get-request :get-next-request]
  :version [0 1 2]
  :port \"any\""
  [^String host ^String community & options]
 (let [o (if (nil? options) nil  (apply hash-map options))
       pdu-type (or (:pdu-type o) :get-bulk-request)
       version (or (:version o) 1)
       port (or (:port o) 161)]
   (fn [oids] {:message (compose-snmp-packet {:community community
                                              :version version 
                                              :pdu ((pdu-type pdu-function) (get-new-rid) oids o)})
               :host (.getHostAddress (InetAddress/getByName host)) 
               :port port})))

(defn snmp-template
  "Function takes map of parameters that are optional. It returns
  SNMP template function with in form of {:message result :port port} that 
  can be merged with host. Result is function that takes RID as input
  and returns composed packet with RID. 

  Intention is to have one UDP channel for multicast UDP traffic."
  [{:keys [pdu-type version port community oids]
    :or {pdu-type :get-request
         version 1
         port 161
         community "public"
         oids [:system]}}] 
  (fn [rid] {:message (compose-snmp-packet {:community community
                                            :version version
                                            :pdu ((pdu-type pdu-function) rid oids)})
             :port port}))


(defn shout 
  "Function \"shouts\" oids to collection of hosts. It openes one
  port through which it sends UDP packets to different targets and
  waits for their response.
  
  Sort of multicast traffic."
  [hosts & {:keys [community port version oids pdu-type send-interval timeout shout-port] 
                                 :or {send-interval 5
                                      timeout 2000}
                                 :as receiver-options}]
  (let [c (get-snmp-channel)
        template-fn (snmp-template receiver-options)
        packets (map #(merge {:host %} (template-fn (get-new-rid))) hosts)
        result (atom nil)]
    (try
      (receive-all @c #(swap! result 
                              (fn [x] (do
                                        (conj x (hash-map :host (:host %)
                                                          :bindings (get-variable-bindings %)))))))
      (doseq [x packets] (do (Thread/sleep send-interval) (enqueue @c x)))
      @(idle-result timeout @c)
      (catch Exception e nil)
      (finally (close @c)))
    @result))


;; Usefull functions
(defn poke [host community & {:keys [timeout oids]
                              :or {timeout 1000
                                   oids [[1 3 6 1 2 1 1 1 0]]}}]
  (let [get-fn (open-line host community :pdu-type :get-request)
        c (get-snmp-channel)]
    (try 
      (enqueue @c (get-fn oids))
      (let [r (read-channel @c) 
            _ (wait-for-result r timeout)
            vb (get-variable-bindings @r)]
        vb)
      (catch Exception e nil)
      (finally (close @c)))))


(defn snmp-get [version host community & oids]
  (let [oids (vec oids)
        get-fn (open-line host community :pdu-type :get-request :version version)
        c (get-snmp-channel)]
    (try
      (enqueue @c (get-fn oids))
      (let [r (read-channel @c)
            _ (wait-for-result r 1000)
            vb (get-variable-bindings @r)]
        vb)
      (catch Exception e nil)
      (finally (close @c)))))

(defn snmp-get-next [version host community & oids]
  (let [oids (vec oids)
        get-fn (open-line host community :pdu-type :get-next-request :version version)
        c (get-snmp-channel)]
    (try
      (enqueue @c (get-fn oids))
      (let [r (read-channel @c)
            _ (wait-for-result r 1000)
            vb (get-variable-bindings @r)]
        vb)
      (catch Exception e nil)
      (finally (close @c)))))

(defn snmp-get-first [version host community & oids]
  (let [oids (vec oids)
        get-fn (open-line host community :pdu-type :get-next-request :version version)
        c (get-snmp-channel)
        transmition-fn (fn [oids]
                         (enqueue @c (get-fn oids))
                         (let [r (read-channel @c)
                               _ (wait-for-result r 1000)
                               vb (get-variable-bindings @r)]
                           vb))]
    (try
      (let [vb-initial (transmition-fn oids)]
        (loop [vb (remove  #(-> % vals first empty?) vb-initial)
               not-found (filter #(-> % vals first empty?) vb-initial)]
          (if (empty? not-found) (sort-by #(-> % keys first) vb)
            (let [new-vb (transmition-fn (map #(-> % keys first) not-found))
                  found-vb (filter #(-> % second empty?) new-vb)
                  empty-vb (remove  #(-> % second empty?) new-vb)]
              (recur (into vb new-vb) empty-vb)))))
      (catch Exception e nil)
      (finally (close @c)))))

(defn snmp-bulk-get [version host community & oids]
  (let [oids (vec oids)
        get-fn (open-line host community :pdu-type :get-bulk-request :version version)
        c (get-snmp-channel)]
    (try
      (enqueue @c (get-fn oids))
      (let [r (read-channel @c)
            _ (wait-for-result r 1000)
            vb (get-variable-bindings @r)]
        vb)
      (catch Exception e nil)
      (finally (close @c)))))



(comment
  "Example usage"
  (def rst18 (open-line "RST18" "spzROh"))
  (def example-channel (get-snmp-channel))
  (def filtered-channel (filter-line "RST18" @example-channel))
  (receive-all filtered-channel show-variable-bindings)
  (enqueue @example-channel (rst18 [:system])))