;; Copyright (c) Stefan A. van der Meer. All rights reserved.
;; The use and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;; can be found in the file epl-v10.html at the root of this distribution. By
;; using this software in any fashion, you are agreeing to be bound by the
;; terms of this license. You must not remove this notice, or any other, from
;; this software.

;;; cljgp/breeding.clj

(ns cljgp.breeding
  "Facilities for generating an initial population and breeding an evaluated
  one, including some standard breeding procedures."
  (:use cljgp.random
	cljgp.util))



;;
;; Tree generation
;;

(defn generate-tree
  "Returns a tree generated from given 'func-set and 'term-set, which have to be
  nth-able. If 'method is :grow, grow method will be used to generate the tree
  up to max-depth in size. Else, 'full' method will be used, resulting in a tree
  with a depth equal to max-depth in all its branches.
  
  For 'max-depth < 0, will return a tree of size 1.

  Throws exception if no legal tree could be generated due to type constraints."
  [max-depth method func-set term-set node-type]
  (if (or (<= max-depth 1)
	  (and (= method :grow)
	       (< (gp-rand) (/ (count term-set)
			       (+ (count term-set) (count func-set))))))
    (if-let [tnode (pick-rand-typed node-type term-set)]
      tnode
      (throw (RuntimeException. 
	      (str "No available terminal of type " node-type))))
    (if-let [fnode (pick-rand-typed node-type func-set)]
      (cons fnode (doall     ; force seq to realize inside try/catch
		   (for [cur-type (:arg-type ^fnode)]
		     (generate-tree (dec max-depth) method 
				    func-set term-set 
				    cur-type))))
      (throw (RuntimeException. 
	      (str "No available function of type " node-type))))))

(defn generate-ramped
  "Returns a tree generated using a version of the ramped half and half
  method. Tree will be generated from the function and terminal sets, up to
  :max-depth (inclusive). The 'grow' method will be used with the probability
  given as :grow-chance.

  If a tree is constructed that ends up not being validly typed, returns
  nil. This can happen quite often in experiments with many different types. See
  cljgp.breeding/get-valid for a way to deal with this."
  [{:keys [max-depth grow-chance] :or {max-depth 7, grow-chance 0.5}} 
   {:as run-config,
    :keys [function-set terminal-set root-type]}]
  (try
   (generate-tree (inc (gp-rand-int max-depth))
		  (if (< (gp-rand) grow-chance) :grow :full)
		  function-set
		  terminal-set
		  root-type)
   (catch RuntimeException e
     nil)))

(defn get-valid
  "Returns a result of 'gen-fn (which should be a tree-generating function) that
  passes the given 'valid-tree? test. If gen-fn returns a vector of trees, all
  are tested (ie. all produced trees must be valid). If gen-fn returns anything
  that is not a vector, it is assumed to be a single tree and tested
  directly. Will retry gen-fn up to 'tries times, if by then no valid result has
  been generated, returns nil."
  [valid-tree? tries gen-fn]
  (first (filter #(if (vector? %)
		    (and (not-empty %) (every? valid-tree? %))
		    (and (not (nil? %)) (valid-tree? %)))
		 (take tries (repeatedly gen-fn)))))

(defn- ind-generator-seq
  "Returns a lazy infinite sequence of individuals with generation 0 and
  expression trees generated from the function- and terminal-set, all as
  specified in the given 'run-config. Used internally by
  cljgp.breeding/generate-pop."
  [{:as run-config,
    :keys [pop-generation-fn, validate-tree-fn, func-template-fn]}]
  (let [retries 1024			; should perhaps be configurable
	generate (fn [] 
		   (if-let [ind (get-valid validate-tree-fn retries
					   #(pop-generation-fn run-config))]
		     ind
		     (throw (RuntimeException. 
			     (str "Failed to generate a valid tree after "
				  retries " attempts. Most likely there is "
				  "an issue that makes valid trees "
				  "impossible to generate.")))))]
    (repeatedly 
     #(make-individual (func-template-fn (generate)) 0))))

; Note that it is intentional that (ind-generator-seq ..) is called inside each
; future, even though one might think it's just a producer function that is
; identical between futures. The reason is that the closure appears to inherit
; the bindings as they are at the point where it is created, as opposed to that
; where it is called. Dynamic bindings are nefarious things.
(defn generate-pop
  "Returns a population of individuals generated from scratch out of the nodes
  in the function set and terminal set, using the tree producer function also
  specified in the 'run-config (typically ramped half and half). The work is
  divided over worker threads, each with their own RNG."
  [{:keys [population-size threads rand-fns] :as run-config}]
  (let [per-future (divide-up population-size threads)]
    (mapcat deref
	    (doall 
	     (map #(future
		     (binding [cljgp.random/gp-rand %2]
		       (doall (take %1 (ind-generator-seq run-config)))))
		  per-future
		  rand-fns)))))

;;
;; Breeding
;;

(defn parent-arg-type
  "Returns the arg-type that the node at the given 'index of the 'tree
  satisfies. In other words: returns the type that a node at the index must
  satisfy in order to be valid. This type is retrieved from the parent
  node's :arg-type metadata. For index 0, returns given 'root-type.

  Throws out of bounds exception if idx is out of bounds."
  [index root-type treeseq]
  (loop [typestack (list root-type)
	 i index
	 nodes treeseq]
    (cond 
      (== i 0) (first typestack)
      (seq nodes) (recur (concat (gp-arg-type (first nodes))
				 (rest typestack))
			 (dec i)
			 (rest nodes))
      :else (throw (IndexOutOfBoundsException. 
		    "Index out of bounds of treeseq.")))))

; The low-level tree handling functions use a somewhat ugly side-effect counter
; while traversing a tree to keep track of the node index. This appears to be
; quite a bit faster than more elegant methods, and these functions are called
; very often. Hence the optimization. Will hopefully be improved in the future.

;;; Older version of parent-arg-type that is most likely obsolete and will be
;;; removed if the new version works out.
(defn parent-arg-type-tree
  "Returns the arg-type that the node at the given 'index of the 'tree
  satisfies. In other words: returns the type that a node at the index must
  satisfy in order to be valid. This type is retrieved from the parent
  node's :arg-type metadata. For index 0, returns given 'root-type.

  Differs from 'parent-arg-type by walking the tree directly instead of using a
  tree-seq. Also, returns nil if idx is out of bounds."
  [index root-type tree]
  (let [i (atom -1)
	pfn (fn ptype [node type]
	      (if (>= (swap! i inc) index)
		type
		(when (coll? node) 
		  (first
		   (remove nil?
			   (doall (map ptype
				       (next node)
				       (:arg-type ^(first node)))))))))]
    (pfn tree root-type)))

(defn tree-replace
  "Returns given 'tree with node at 'idx replaced by 'new-node."
  [idx new-node tree]
  (let [i (atom -1)
	r-fn (fn do-replace [node]
	       (swap! i inc)
	       (cond
		 (= @i idx) new-node
		 (> @i idx) node	; means idx has already been replaced
		 (coll? node) (cons (first node)
				    (doall (map do-replace (next node))))
		 :else node))]
    (r-fn tree)))

;;; Moved some code out of crossover-uniform-typed into a macro, not useful on
;;; its own.
(defmacro find-valid-indices
  "Helper macro for internal use by crossover-uniform-typed.
  Given a tree and some type information, finds all indices of nodes in the
  tree-seq representation of the tree that can be safely exchanged with a node
  whose type information is described in the replacement-type and
  replacement-parent-type arguments."
  [treeseq replacement-type replacement-parent-type root-type]
  `(filter (fn [idx#] (and (isa? (gp-type (nth ~treeseq idx#))
				 ~replacement-parent-type)
			   (isa? ~replacement-type
				 (parent-arg-type idx# ~root-type ~treeseq))))
	   (range (count ~treeseq))))

(defn crossover-uniform-typed
  "Performs a subtree crossover operation on the given trees, taking node types
  into account. The 'root-type is the type satisfied by the root nodes of both
  trees. Returns vector of two new trees, or nil if crossover failed."
  [[tree-a tree-b] root-type]
  (let [seq-a (make-tree-seq tree-a)
	idx-a (gp-rand-int (count seq-a))
	pick-a (nth seq-a idx-a)

	type-a (gp-type pick-a)
	parent-type-a (parent-arg-type idx-a root-type seq-a)
	;; Convert to vec for faster nth in find-valid-indices
	seq-b (make-tree-seq tree-b)

	;; Find indices of nodes that can safely be switched in for pick-a
	valid-indices (find-valid-indices seq-b type-a parent-type-a root-type)]
    (when (seq valid-indices)
      (let [idx-b (pick-rand valid-indices)
	    pick-b (nth seq-b idx-b)]
	[(tree-replace idx-a pick-b tree-a)
	 (tree-replace idx-b pick-a tree-b)]))))

;;; Mutate used to return the unmodified tree if generating a subtree
;;; failed. However, this did not fit in well with the higher level breeder
;;; functions, as they should be the ones to decide whether to retry the
;;; mutation or return the original tree (based on eg. :breeding-retries).
(defn mutate
  "Performs a mutation operation on the given tree, selecting a mutation point
  uniformly and generating a new subtree to replace it (from the given 'func-set
  and 'term-set, up to 'max-depth). Also requires the 'root-type as the mutation
  point may be the root.

  Returns the new tree. If no valid subtree could be generated, returns
  nil."
  [tree max-depth func-set term-set root-type]
  (let [tree-seq (make-tree-seq tree)
	idx (gp-rand-int (count tree-seq))
	pick-type (parent-arg-type idx root-type tree-seq)
	subtree (try (generate-tree max-depth :grow func-set term-set pick-type)
		     (catch RuntimeException e nil))]
    (if (nil? subtree)
      nil
      (tree-replace idx subtree tree))))

(defn crossover-individuals
  "Performs a crossover operation on the given individuals using given
  'crossover-fn. Returns vector of two new individuals. If crossover-fn returns
  nil after the number of breeding retries configured in the 'run-config, then
  the given individuals are reproduced directly."
  [crossover-fn inds {:as run-config
		      :keys [validate-tree-fn func-template-fn
			     breeding-retries root-type]}]
  (let [orig-trees (map (comp get-fn-body get-func) inds)
	new-trees (get-valid validate-tree-fn breeding-retries
			     #(crossover-fn orig-trees root-type))
	gen (inc (get-gen (first inds)))]
    (vec (map #(make-individual (func-template-fn %) gen)
	      (if (nil? new-trees)
		orig-trees		; crossover failed, copy
		new-trees)))))		; successfull crossover

(defn mutate-individual
  "Performs mutation on given individual's tree. Returns seq with the new
  individual as single element (for easy compatibility with crossover-ind)."
  [ind max-depth {:as run-config
		  :keys [validate-tree-fn breeding-retries func-template-fn
			 function-set terminal-set root-type]}]
  (let [orig (get-fn-body (get-func ind))
	new (get-valid validate-tree-fn breeding-retries
		       #(mutate orig 
				max-depth
				function-set terminal-set
				root-type))
	tree (if (nil? new) orig new)
	gen (inc (get-gen ind))]
    [(make-individual (func-template-fn tree) gen)]))


; The reason why we can't just assoc a new gen into the individual is that
; other keys may have been added earlier (eg. a :fitness value) that should
; not exist on a newly bred individual. We could explicitly unset those but
; that would take otherwise unnecessary maintenance if more keys turn up.
(defn reproduce-individual
  "Performs direct reproduction, ie. breeds a new individual by directly copying
  the given individual's tree. Returns seq with new individual as single
  element."
  [ind {:as run-config,
	func-tpl :func-template-fn}]
  (let [tree (get-fn-body (get-func ind))
	gen (inc (get-gen ind))]
    [(make-individual (func-tpl tree) gen)]))


(defn crossover-breeder
  "Selects two individuals from pop by applying the selection-fn specified in
   run-config to it twice, performs crossover and returns seq of two resulting
   new trees."
  [pop {:as run-config,
	select :selection-fn}]
  (crossover-individuals crossover-uniform-typed
			 [(select pop) (select pop)]
			 run-config))

(defn mutation-breeder
  "Selects an individual from pop by applying selection-fn specified in the
  run-config to it, performs mutation and returns seq with the single resulting
  tree in it. Mutation will pull nodes from the function and terminal sets
  specified in run-config. Generated (sub)tree will be at most max-depth deep."
  ([{max-depth :max-depth} pop {:as run-config,
				select :selection-fn}]
     (mutate-individual (select pop) max-depth run-config))
  ([pop run-config]
     (mutation-breeder {:max-depth 17} pop run-config)))

(defn reproduction-breeder
  "Selects an individual from pop by applying selection-fn specified in the
  run-config to it, returns seq with a new individual whose tree is identical to
  the selected individual."
  [pop {:as run-config,
	select :selection-fn}]
  (reproduce-individual (select pop) run-config))

(defn- setup-breeders
  "Sets up (lazy-)seq of breeders (maps with a :prob key) in a seq in
  increasing summation, eg. three sources with probabilities [0.1 0.5 0.4] will
  become [0.1 0.6 1.0]. This makes source selection fast and easy. Probabilities
  have to add up to 1.0."
  [breeders] 
  (let [pr (fn process [bs p]
	     (when-let [s (seq bs)]
	       (let [b (first bs)
		     p-new (+ p (:prob b))]
		 (cons (assoc b :prob p-new)
		       (process (rest s) p-new)))))]
    (pr breeders 0)))

(defn- select-breeder
  "Returns a random breeder from a collection of them according to their :prob
  values. See 'setup-breeders."
  [breeders]
  (let [p (gp-rand)]
    (first (filter #(< p (:prob %)) breeders))))

(defn breed-new-pop
  "Returns a new population of equal size bred from the given evaluated pop by
   repeatedly applying a random breeder to pop-evaluated. Work is divided over
   worker threads."
  [pop-evaluated {:as run-config,
		  :keys [breeders population-size threads rand-fns]}]
  (let [bs (setup-breeders breeders)
	per-future (divide-up population-size threads)
	breed-generator (fn breed-new []
			  (lazy-seq
			    (when-let [breed (:breeder-fn (select-breeder bs))]
			      (concat (breed pop-evaluated run-config) 
				      (breed-new)))))]
    (mapcat deref
	    (doall
	     (map #(future
		     (binding [cljgp.random/gp-rand %2]
		       (doall (take %1 (breed-generator)))))
		  per-future
		  rand-fns)))))

