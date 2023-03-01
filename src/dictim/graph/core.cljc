(ns dictim.graph.core
  (:require [clojure.string :as str]))




;; d2 needs nodes referred to in edges to be fully qualified, e.g. cluster1.cluster2.node
;; if that edge is not placed inside the right cluster tree.

(defn- cluster-path
  [cluster-id cluster-id->parent-id]
  (letfn [(up [parts]
            (let [parent-id (cluster-id->parent-id (first parts))]
              (if (or (= parent-id (first parts)) ;; prevent stack-overflow
                      (nil? parent-id))
                parts
                (up (cons parent-id parts)))))]
    (-> cluster-id list up)))


(defn- part->str [p]
  (cond
    (keyword? p)     (name p)
    :else            (str p)))


(defn- join-parts
  [parts]
  (let [joined (apply str (interpose "." (map part->str parts)))]
    (if (every? keyword? parts)
      (keyword joined)
      joined)))


(defn- qualify-node
  "Qualifies a node to its place in the cluster hierarchy."
  [node-id node-id->cluster-id cluster-id->parent-id]  
  (if-let [clstrid (node-id->cluster-id node-id)]
    (-> (cluster-path clstrid cluster-id->parent-id)
        (concat [node-id])
        join-parts)
    node-id))


(defn- qualify-cluster
  "Qualifies a cluster to its place in the cluster hierarchy"
  [cluster-id cluster-id->parent-id]
  (-> (cluster-path cluster-id cluster-id->parent-id)
      join-parts))


;;;
(defn- remove-nil
  "Prunes the nils and converts to a vector"
  [coll]
  (->> coll
       (remove #(or (nil? %) (and (coll? %) (empty? %))))
       (into [])))


(defn- format-edge [sk dk directed? label attrs
                    {:keys [src-node?
                            dest-node?
                            node-key->cluster
                            cluster->parent
                            qualify?] :as context}]
  
  (if qualify?
    (->> [(if src-node?
            (qualify-node sk node-key->cluster cluster->parent)
            (qualify-cluster sk cluster->parent))
          
          (if directed? "->" "--")

          (if dest-node?
            (qualify-node dk node-key->cluster cluster->parent)
            (qualify-cluster dk cluster->parent))

          label

          (if (empty? attrs) nil attrs)]
         
         remove-nil)

    (->> [sk
          
          (if directed? "->" "--")

          dk

          label

          (if (empty? attrs) nil attrs)]
         
         remove-nil)))


(defn- format-node [k {:keys [label] :as attrs}]
  (let [ats (dissoc attrs :label)]
    (->> [k label (if (empty? ats) nil ats)]
         remove-nil)))


;;; Clusters

;;; build a tree of the cluster edges

(defn- build-tree [edges]
  (letfn [(nodes [p] (map first (filter #(= p (second %)) edges)))
          (down [m p] (let [children (nodes p)]
                        (if (seq children)
                          (assoc m p (into {} (map #(down {} %) children)))
                          (assoc m p nil))))]
    (-> (down {} nil) first second)))


;; layout

(defn- layout-nodes [node->key cluster cluster->nodes node->attrs]
  (mapv
   (fn [n]
     (format-node (node->key n) (node->attrs n)))
   (cluster->nodes cluster)))


(defn- layout-edges [edges
                     & {:keys [edge->src-key
                               edge->dest-key
                               edge->attrs
                               node->key
                               node?
                               node-key->cluster
                               cluster->parent
                               qualify?]
                        :as fns}]
  (map (fn [e]
         (let [s (edge->src-key e)
               d (edge->dest-key e)
               attrs (edge->attrs e)
               directed? (or (:directed? attrs) true)
               label (:label attrs)]

           (format-edge s d directed? label
                        (dissoc attrs :label :directed?)
                        {:src-node? (node? s)
                         :dest-node? (node? d)
                         :node-key->cluster node-key->cluster
                         :cluster->parent cluster->parent
                         :qualify? qualify?})))
       edges))


;;; subgraphs/ clusters

(defn- subgraphs [cluster subtree
                  cluster->attrs cluster->nodes
                  node->key node->attrs]
  
  (let [attrs (cluster->attrs cluster)]
    (->> (concat
          (->> (concat [cluster
                        (:label attrs)
                        (dissoc attrs :label)]
                       (layout-nodes node->key cluster cluster->nodes node->attrs))
               remove-nil)

          (cond
            (nil? subtree)  nil

            (map? subtree)  (map
                             (fn [[cluster subtree]]
                               (subgraphs cluster subtree
                                          cluster->attrs cluster->nodes
                                          node->key node->attrs))
                             subtree)))
         
         remove-nil)))


;;
(defn- tree-edges [nodes ->parent]
  (reduce
   (fn [acc cur]
     (let [p (->parent cur)]
       (if (not= p cur)
         (if p
           (into (conj acc [cur p]) (tree-edges [p] ->parent))
           (conj acc [cur p]))
         (conj acc [cur nil]))))
   #{}
   nodes))



;;; the public function `graph->dictim`

(defn graph->dictim
  ([nodes edges] (graph->dictim nodes edges nil))
  
  ([nodes edges
    {:keys [node->key
            node->attrs
            edge->src-key
            edge->dest-key
            edge->attrs
            node->cluster
            cluster->parent
            cluster->attrs
            qualify?]
     :or {node->key identity
          node->attrs (constantly nil)
          edge->src-key #(or (:src %) (first %))
          edge->dest-key #(or (:dest %) (second %))
          edge->attrs (constantly nil)
          node->cluster (constantly nil)
          cluster->parent (constantly nil)
          cluster->attrs (constantly nil)
          qualify? true}
     :as graph-descriptor}]

   (let [cluster->nodes (when node->cluster
                          (group-by node->cluster nodes))


         ;; build the hierarchy of clusters in the graph
         cluster-tree (when (and cluster->nodes) ; cluster->parent
                        (-> cluster->nodes
                            (dissoc nil) ;; process nodes not in clusters later
                            keys
                            (tree-edges cluster->parent)
                            build-tree))

         node? #(contains? (set (map node->key nodes)) %)

         node-key->cluster (fn [k] (node->cluster (first (filter #(= k (node->key %)) nodes))))]

     (concat

      ;; layout clusters
      (map (fn [[cluster children]]
             (subgraphs cluster children
                        cluster->attrs cluster->nodes
                        node->key node->attrs))
           cluster-tree)

      ;; lay out any remaining nodes not in clusters
      (layout-nodes node->key nil cluster->nodes node->attrs)


      ;; all edges. It's easier to lay out all edges in one go using their qualified names referring to a
      ;; node's position in the cluster hierarchy e.g. x.y.b rather than try to position the edges
      ;; into the d2 cluster hierarchy.
      (layout-edges edges
                    {:edge->src-key edge->src-key 
                     :edge->dest-key edge->dest-key
                     :edge->attrs edge->attrs
                     :node->key node->key
                     :node? node?
                     :node-key->cluster node-key->cluster
                     :cluster->parent cluster->parent
                     :qualify? qualify?})))))
