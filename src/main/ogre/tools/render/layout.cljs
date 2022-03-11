(ns ogre.tools.render.layout
  (:require ogre.tools.form.core
            [ogre.tools.render.canvas :refer [canvas]]
            [ogre.tools.render.toolbar :refer [toolbar]]
            [ogre.tools.render.panel :refer [container]]
            [ogre.tools.state :refer [use-query]]
            [ogre.tools.render.tokens :refer [tokens]]
            [ogre.tools.render.workspaces :refer [workspaces]]))

(def attrs
  [:root/loaded?
   :root/host?
   [:root/shortcuts? :default true]
   [:root/tooltips? :default true]])

(defn layout []
  (let [[result] (use-query {:pull attrs})
        {:root/keys [loaded? host? shortcuts? tooltips?]} result
        classes
        {:global--host       host?
         :global--guest      (not host?)
         :global--shortcuts  shortcuts?
         :global--tooltips   tooltips?}]
    (if loaded?
      (if host?
        [:div.layout {:css classes}
         [:div.layout-workspaces [workspaces]]
         [:div.layout-canvas [canvas]]
         [:div.layout-toolbar [toolbar]]
         [:div.layout-tokens [tokens]]
         [:div.layout-panel [container]]]
        [:div.layout {:css classes}
         [:div.layout-canvas [canvas]]]))))
