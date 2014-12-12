(ns onyx.log.commands.volunteer-for-task
  (:require [clojure.core.async :refer [chan go >! <! close!]]
            [clojure.set :refer [union difference map-invert]]
            [clojure.data :refer [diff]]
            [com.stuartsierra.component :as component]
            [onyx.log.commands.common :as common]
            [onyx.peer.task-lifecycle :refer [task-lifecycle]]
            [onyx.extensions :as extensions]
            [taoensso.timbre]))

(defmulti select-task
  (fn [replica job]
    (get-in replica [:task-schedulers job])))

(defn incomplete-tasks [replica job]
  (let [tasks (get-in replica [:tasks job])
        completed (get-in replica [:completions job])]
    (filter identity (second (diff completed tasks)))))

(defmethod select-task :onyx.task-scheduler/greedy
  [replica job]
  (first (incomplete-tasks replica job)))

(defmethod select-task :onyx.task-scheduler/round-robin
  [replica job]
  (let [allocations (get-in replica [:allocations job])]
    (->> (incomplete-tasks replica job)
         (map (fn [t] {:task t :n (count (get allocations t))}))
         (sort-by :n)
         (first)
         :task)))

(defmethod select-task :default
  [replica job]
  (throw (ex-info (format "Task scheduler %s not recognized"
                          (get-in replica [:task-schedulers job]))
                  {:replica replica})))

(defmulti select-job
  (fn [{:keys [args]} replica]
    (:job-scheduler replica)))

(defmethod select-job :onyx.job-scheduler/greedy
  [{:keys [args]} replica]
  (let [job (first (common/incomplete-jobs replica))]
    (if job
      (let [task (select-task replica job)]
        (-> replica
            (common/remove-peers args)
            (update-in [:allocations job task] conj (:id args))
            (update-in [:allocations job task] vec)))
      replica)))

(defmethod select-job :onyx.job-scheduler/round-robin
  [{:keys [args]} replica]
  (if-not (common/saturated-cluster? replica)
    (let [candidates (common/incomplete-jobs replica)
          job (or (common/find-job-needing-peers replica candidates)
                  (common/round-robin-next-job replica candidates))]
      (if job
        (let [task (select-task replica job)]
          (-> replica
              (common/remove-peers args)
              (update-in [:allocations job task] conj (:id args))
              (update-in [:allocations job task] vec)))
        replica))
    replica))

(defmethod select-job :default
  [_ replica]
  (throw (ex-info (format "Job scheduler %s not recognized" (:job-scheduler replica))
                  {:replica replica})))

(defmethod extensions/apply-log-entry :volunteer-for-task
  [entry replica]
  (select-job entry replica))

(defmethod extensions/replica-diff :volunteer-for-task
  [{:keys [args]} old new]
  (let [allocation (second (diff (:allocations old) (:allocations new)))]
    {:job (first (keys allocation))
     :task (first (keys (get allocation (first (keys allocation)))))}))

(defmethod extensions/reactions :volunteer-for-task
  [{:keys [args]} old new diff peer-args])

(defmethod extensions/fire-side-effects! :volunteer-for-task
  [{:keys [args]} old new diff state]
  (if (and (= (:id args) (:id state)) (:job diff) (:task diff)
           (or (not= (:job state) (:job diff))
               (not= (:task state) (:task diff))))
    (do (when (:lifecycle state)
          (component/stop (:lifecycle state)))
        (let [new-state (assoc state :job (:job diff) :task (:task diff))
              new-lifecycle (component/start ((:task-lifecycle-fn state) diff new-state))
              seal-ch (chan)]
          (assoc new-state :lifecycle new-lifecycle :seal-response-ch seal-ch)))
    state))

