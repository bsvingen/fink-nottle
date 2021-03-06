(ns fink-nottle.sqs.channeled
  (:require [fink-nottle.sqs :as sqs]
            [fink-nottle.internal.util :as util]
            [clojure.core.async :as a :refer [<! >! go]]
            [glossop :as g]))

(defn receive! [creds queue-url & [{:keys [chan] :as params}]]
  (let [{:keys [maximum] :as params} (merge {:maximum 10 :wait-seconds 20} params)
        chan (or chan (a/chan maximum))]
    (go
      (loop []
        (let [messages (try
                         (-> (sqs/receive-message! creds queue-url params) g/<?)
                         (catch Exception e
                           (>! chan e)
                           ::error))]
          (if (and (not= messages ::error)
                   (or (empty? messages)
                       (<! (util/onto-chan? chan messages))))
            (recur)
            (a/close! chan)))))
    chan))

(defn identify-batch [messages]
  (map-indexed
   (fn [i {:keys [id] :as m}]
     (cond-> m (not id) (assoc :id (str i))))
   messages))

(defn- failure->throwable [{:keys [code] :as failure}]
  (ex-info (name code) (assoc failure :type code)))

(defn- batch-send! [issue-fn batch error-chan]
  (g/go-catching
    (try
      (let [{:keys [failed]} (g/<? (issue-fn (identify-batch batch)))]
        (when-let [exs (some->> failed vals (map failure->throwable) not-empty)]
          (<! (a/onto-chan error-chan exs false))))
      (catch Exception e
        (>! error-chan e)))))

(defn- batch-cleanup! [issue-fn batch error-chan]
  (go
    (when (not-empty batch)
      (<! (batch-send! issue-fn batch error-chan)))
    (a/close! error-chan)))

(defn batching-channel*
  [issue-fn
   & [{:keys [period-ms threshold in-chan error-chan timeout-fn]
       :or {period-ms 200 threshold 10 timeout-fn a/timeout}}]]
  (let [in-chan    (or in-chan (a/chan))
        error-chan (or error-chan (a/chan))]
    (go
      (loop [batch []]
        (let [msg (if (not-empty batch)
                    (a/alt!
                      (timeout-fn period-ms) ::timeout
                      in-chan ([v] v))
                    (<! in-chan))]
          (if (nil? msg)
            (<! (batch-cleanup! issue-fn batch error-chan))
            (let [batch (cond-> batch (not= msg ::timeout) (conj msg))]
              (if (or (= threshold (count batch)) (= msg ::timeout))
                (do (<! (batch-send! issue-fn batch error-chan))
                    (recur []))
                (recur batch)))))))
    {:in-chan in-chan :error-chan error-chan}))

(defn batching-sends [creds queue-url]
  (batching-channel*
   (partial sqs/send-message-batch! creds queue-url)))
(defn batching-deletes [creds queue-url]
  (batching-channel*
   (partial sqs/delete-message-batch! creds queue-url)))
