;; Copyright (c) Stefan A. van der Meer. All rights reserved.
;; The use and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;; can be found in the file epl-v10.html at the root of this distribution. By
;; using this software in any fashion, you are agreeing to be bound by the
;; terms of this license. You must not remove this notice, or any other, from
;; this software.

(ns test.cljgp.test-config
  (:use clojure.contrib.test-is
	test.helpers
	cljgp.config
	cljgp.util
	cljgp.core))

(deftest test-prim
  (let [m {:foo 27723 :bar true}]
    (is (= (meta (with-meta 'x m))
	   (meta (prim 'x m))))))

(deftest test-make-simple-end
  (let [pop [(assoc (make-individual '() 10) :fitness 5) 
	     (assoc (make-individual '() 10) :fitness 4.9999)]]
    (testing "generation limit"
	     (is ((make-simple-end 9) pop))
	     (is ((make-simple-end 10) pop))
	     (is (not ((make-simple-end 11) pop))))
    (testing "fitness limit"
	     (is ((make-simple-end 99 6) pop))
	     (is ((make-simple-end 99 4.9999) pop))
	     (is (not ((make-simple-end 99 4.5) pop)))
	     (is (not ((make-simple-end 99 0.1) pop))))))

(deftest test-seeds-from-time
  (is (every? number? (take 50 (seeds-from-time))))
  (is (not= ""
	    (with-out-str (dorun (take 5 (seeds-from-time true)))))))

(deftest test-strict-every?
  (is (strict-every? true? [true true true]))
  (is (not (strict-every? true? [true false true])))
  (is (not (strict-every? true? []))))

; Trying to be exhaustive with these tests as correct config validation can
; prevent many bugs elsewhere.
(deftest test-validators
  (testing "valid-func-entry?"
	   (let [vfe valid-func-entry?]
	     (is (vfe (prim `+ {:arg-type [Number Number]})))
	     (is (vfe (prim 'x {:arg-type [Number Number]})))
	     (is (not (vfe 'x)))
	     (is (not (vfe (prim 'x {}))))

	     (is (not (vfe {:sym 'a :arity 2}))) ; old entry representation
	     (is (not (vfe {:sym `+ :arity 4})))
	     (is (not (vfe [])))))
  (testing "valid-term-entry?"
	   (let [vte valid-term-entry?]
	     (is (vte 'x))
	     (is (vte (prim 'x {})))
	     (is (vte (prim 'x nil)))
	     (is (not (vte +)))
	     (is (not (vte 1)))

	     (is (not (vte {:sym 'x}))) ; old repr.
	     (is (not (vte [])))))
  (testing "valid-breeder-entry?"
	   (let [vbe valid-breeder-entry?
		 b-fn #(println "mock breeder")]
	     (is (vbe {:prob 0.1 :breeder-fn b-fn}))
	     (is (not (vbe {})))
	     (is (not (vbe [])))
	     (is (not (vbe {:prob 1})))
	     (is (not (vbe {:breeder-fn b-fn})))
	     (is (not (vbe {:prob true :breeder-fn b-fn})))
	     (is (not (vbe {:prob 1 :breeder-fn 'foo}))))))

(deftest test-check-key
  (let [conf (dissoc config-maths :func-template-fn)]
    (is (nil? (check-key :foo true? conf))
	"If a key does not exist and there is no default, fail.")
    (is (nil? (check-key :breeders (constantly false) conf))
	"If key exists but fails test, fail.")
    (is (= (check-key :population-size number? conf) 
	   [:population-size (:population-size conf)])
	"If key exists and val passes test, return map entry.")
    (is (= (quiet (check-key :func-template-fn true? conf)) 
	   (find config-defaults :func-template-fn))
	"If key does not exist and default does, return default entry.")))

(deftest test-check-config
  (let [conf (dissoc config-maths :func-template-fn)
	conf-broken (dissoc config-maths :function-set)]
    (is (= (quiet (check-config conf)) 
	   (assoc conf :func-template-fn (:func-template-fn config-defaults)))
	"Missing key should be replaced by default if one exists.")
    (is (thrown? Exception (check-config conf-broken))
	"Missing key with no default should result in exception.")
    (is (thrown? Exception 
		 (check-config (assoc conf-broken :function-set [])))
	"Invalid value for key should result in exception.")))


(deftest test-assert-constraints
  (let [conf-broken (assoc config-maths :threads 999)]
    (is (thrown? Exception (assert-constraints conf-broken))
	"Too many threads for the number of seeds should fail.")
    (is (= config-maths (assert-constraints config-maths))
	"Valid config should be returned with no exceptions thrown.")))
