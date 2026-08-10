(ns podbay.comms.core
  (:require [reagent.dom.client :as rdc]
            [podbay.comms.state :as state]
            [podbay.comms.views :as views]))

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn- render []
  (rdc/render root [views/app]))

(defn ^:dev/after-load re-render []
  (render))

(defn init []
  (state/init!)
  (render))
