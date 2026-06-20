(ns hooks.with-db
  "clj-kondo hook teaching it that `(with-db [conn] body)` binds `conn` over the
   body, so the symbol resolves during analysis. Rewrites the call to a `let`."
  (:require [clj-kondo.hooks-api :as api]))

(defn with-db [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        sym (first (:children binding-vec))]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node [sym (api/token-node nil)])
                   body))}))
