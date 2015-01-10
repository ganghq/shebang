

var app = angular.module('shebang', []);


app.run(function () {
  if (window.console) {
    console.log("#! hello console");
  }
});





app.controller('MainCtrl', function ($scope) {

  $scope.yep = "KILL LA KILL";

});
