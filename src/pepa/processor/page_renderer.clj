(ns pepa.processor.page-renderer
  (:require [com.stuartsierra.component :as component]
            [pepa.db :as db]
            [pepa.pdf :as pdf]
            [pepa.processor :as processor :refer [IProcessor]]
            [pepa.log :as log])
  (:import java.security.MessageDigest
           java.math.BigInteger))

(def +hashing-algorithm+ (MessageDigest/getInstance "SHA-256"))

(defn ^:private hash-data
  "Hashes the byte-array BS (returns a string)."
  [bs]
  (let [md (.clone +hashing-algorithm+)]
    (.update md bs)
    (format "%032x" (BigInteger. 1 (.digest md)))))

(defn ^:private render-page [renderer dpis page]
  (try
    (pdf/with-reader [pdf (:data page)]
      ;; Render images in all configured DPI settings
      (mapv (fn [dpi]
              (log/debug "Rendering" (:id page) "with" dpi "dpi")
              (let [image (pdf/render-page pdf (:number page) :png dpi)]
                {:page (:id page)
                 :dpi dpi
                 :image image
                 :hash (hash-data image)}))
            dpis))
    (catch Exception e
      (log/error "Rendering failed:" (str e)))))


(defrecord PageRenderer [config db processor]
  IProcessor
  (next-item [component]
    "SELECT p.id, p.number, f.data
     FROM pages AS p
     JOIN files AS f ON p.file = f.id
     WHERE p.render_status = 'pending'
     ORDER BY p.number, p.id
     LIMIT 1")

  (process-item [component page]
    (log/info "Rendering page" (:id page))
    (let [db (:db component)
          config (:config component)
          dpis (set (-> config :rendering :png :dpi))
          images (render-page component dpis page)]
      (db/with-transaction [db db]
        (let [status (if images
                       (do (db/insert-multi! db :page_images images)
                           :processing-status/processed)
                       :processing-status/failed)]
          (db/notify! db :pages/updated)
          (db/update! db
                      :pages
                      {:render_status status}
                      ["id = ?" (:id page)])))))

  component/Lifecycle
  (start [component]
    (log/info "Starting page renderer")
    (assoc component
           :processor (processor/start component :pages/new)))

  (stop [component]
    (log/info "Stopping page renderer")
    (when-let [processor (:processor component)]
      (processor/stop processor))
    (assoc component
           :processor nil)))

(defn ^:private rerender-all! [component]
  (db/with-transaction [db (:db component)]
    (db/delete! db :page_images [])
    (db/update! db :pages {:render_status :processing-status/pending} [])
    (db/notify! db :pages/updated)
    (db/notify! db :pages/new)))

(defn make-component []
  (map->PageRenderer {}))
