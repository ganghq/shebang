var app = angular.module('shebang', ['ngWebSocket']);
/** @namespace conf.wsUrl */

var conf = conf || {};
//noinspection JSUndefinedPropertyAssignment
conf.debug = true;

app.run(function () {
    if (conf.debug && window.console) {
        console.log("#! hello console");
        console.log("conf.wsUrl: ", conf.wsUrl);
        console.log("conf.debug: ", conf.debug);
    }
});

app.factory('chatApi', function ($websocket) {
    // Open a WebSocket connection
    var ws = $websocket(conf.wsUrl);
    var posts = [];

    ws.onMessage(function (message) {
        if (conf.debug && window.console) {
            console.log("message received: ", message);
        }
        var message = JSON.parse(message.data);

        if (message.type === "message") {
            posts.unshift(message);
        } else {
            if (window.console) {
                console.error("Unsupported message type. data: ", message);
            }
        }
    });


    /**
     * Sends chat message for current user
     * @param message
     * @param channel
     */
    function send(message, channel) {
        var data = JSON.stringify({msg: message, channel: channel});

        if (conf.debug && window.console) {
            console.log("message send: ", data);
        }
        ws.send(data)
    }

    return {
        posts: posts, //todo makes this immutable
        send: send,
        ws: ws
    }
});

app.controller('MainCtrl', function ($scope, chatApi) {
    /**
     * Message input field id
     * @const {string}
     */
    var MESSAGE_INPUT_ID = "message_input";

    function send(message) {

        //do send
        chatApi.send(message, "#test");

        clearFocus()
    }

    function clearFocus() {
        //set focus to input field
        document.getElementById(MESSAGE_INPUT_ID).focus();

        //clear message input
        $scope.message = "";
    }


    chatApi.ws.onOpen(function () {
        $scope.connected = true
    });

    chatApi.ws.onClose(function () {
        $scope.connected = false
    });

    /* INIT */
    clearFocus();

    /* EXPORT */
    $scope.connected = false;
    $scope.MESSAGE_INPUT_ID = MESSAGE_INPUT_ID;
    $scope.posts = chatApi.posts;
    $scope.send = send;
    $scope.conf = conf;

});
