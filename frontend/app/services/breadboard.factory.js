import './websocket.factory';
import 'angular';

BreadboardFactory.$inject = ['websocketFactory', '$rootScope', '$cookieStore', '$http', '$q', 'configService', '$timeout'];
export default function BreadboardFactory($websocketFactory, $rootScope, $cookieStore, $http, $q, configService, $timeout) {

  let websocket;
  let nodeChangeListeners = [];

  function processMessage(data, callback) {
    //console.log('processMessage', data);
    if (data.action !== undefined) {
      const g = $rootScope.$$childHead.breadboardGraph;
      if(data.action === "addNode")
        g.addNode(data.id);

      if(data.action === "removeNode")
        g.removeNode(data.id);

      if(data.action === "nodePropertyChanged") {
        // TODO: Do we ever send node property values as JSON?
        let value = "";
        try {
          value = JSON.parse(data.value);
        } catch(e) {
          value = data.value;
        }
        g.nodePropertyChanged(data.id, data.key, value);
      }

      if(data.action === "nodePropertyRemoved")
        g.nodePropertyChanged(data.id, data.key);

      if(data.action === "addLink")
        g.addLink(data.id, data.source, data.target, data.value);

      if(data.action === "removeLink")
        g.removeLink(data.id, data.source, data.target);

      if (data.action === "linkPropertyChanged") {
        g.linkPropertyChanged(data.id, data.key, data.value);
      }
      if (data.action === "linkPropertyRemoved") {
        g.linkPropertyRemoved(data.id, data.key);
      }
      if (data.action === 'addNode' ||
          data.action === 'removeNode' ||
          data.action === 'nodePropertyChanged' ||
          data.action === 'nodePropertyRemoved') {
        for (let i = 0; i < nodeChangeListeners.length; i++) {
          let nodes = g.getNodes();
          //console.log('nodes', nodes);
          nodeChangeListeners[i].call(window, nodes);
        }
      }
    } else {
      $rootScope.$apply(function () {
        //console.log("data", data);
        callback(data);
      });
    }
  }

  let service = {
    async onmessage (callback) {
      const config = await window.Breadboard.loadConfig()
      websocket = await window.Breadboard.connect()
      websocket.onmessage = function(message){
        let data = JSON.parse(message.data);
        if (data.hasOwnProperty("queuedMessages")) {
          for (var i = 0; i < data.queuedMessages.length; i++) {
            processMessage(data.queuedMessages[i], callback);
          }
        } else {
          processMessage(data, callback);
        }
      };
      websocket.onopen = function (evt) {
        websocket.send(JSON.stringify({ action: 'LogIn', uid: config.uid }));
      };
    },
    send: function (message) {
      configService.get('uid').then(function(uid) {
        message.uid = uid;
        websocket.send(JSON.stringify(message));
      });
    },
    addNodeChangeListener: function(listener) {
      //console.log('listener added', listener);
      nodeChangeListeners.push(listener);
    }
  };

  return service;
}
