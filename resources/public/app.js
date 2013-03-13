angular.module('myFilters', []).
    filter('relDate', function() {
        return function(dstr) {
            return moment(dstr).fromNow();
        };
    }).
    filter('calDate', function() {
        return function(dstr) {
            return moment(dstr).calendar();
        };
    }).
    filter('bytes', function() {
        return function(s) {
            if (s < 10) {
                return s + "B";
            }
            var e = Math.floor(Math.log(parseInt(s, 10)) / Math.log(1024));
            var sizes = ["B", "KB", "MB", "GB", "TB", "PB", "EB"];
            var suffix = sizes[e];
            var val = s / Math.pow(1024, Math.floor(e));
            return val.toFixed(2) + suffix;
        };
    });

var bbmodule = angular.module('buildboard', ['myFilters']).
config(['$routeProvider', '$locationProvider',
        function($routeProvider, $locationProvider) {
            $routeProvider.
                when('/builds/', { templateUrl: '/partials/all-builds.html',
                                   controller: 'BuildListCtrl'}).
                when('/buildinfo/:build', { templateUrl: '/partials/build-detail.html',
                                            controller: 'BuildDetailCtrl'}).
                when('/compare/:builda/:buildb', { templateUrl: '/partials/build-compare.html',
                                                     controller: 'BuildCompareCtrl'}).
                otherwise({redirectTo: '/builds/'});
        }]);

bbmodule.factory('buildyRT', function($http, $timeout) {
    var recentMessages = [];

    function poll() {
        $http.get("/scrape-queue").success(function(resp) {
            if(resp !== null) {
                console.log("got ", resp);
                recentMessages = recentMessages.concat(resp);
                poll();
            } else {
                console.log("got null");
                //should only get false/nil if we don't have a queue.
                $http.get("/ensure-queue").success(function(resp) {
                });
            }
        }).error(function(err) {
            $timeout(poll, 10000);
        });
    }

    $http.get("/ensure-queue").success(function(resp) {
        poll();
    });

    function bykind(kind) {
        return _.filter(recentMessages, function(m) { return m && m.kind === kind; });
    }

    function clear() {
        recentMessages = [];
    }

    function all() {
        return recentMessages;
    }

    return {
        all: all,
        bykind: bykind,
        clear: clear
    };
});

function GitStatusCtrl($scope, buildyRT) {
    $scope.rt = buildyRT;
    buildyRT.clear();
}

function BuildCompareCtrl($scope, $http, $routeParams, $rootScope, buildyRT) {
    $rootScope.title = "Build " + $routeParams.builda + " vs. " + $routeParams.buildb;
    $scope.builda = $routeParams.builda;
    $scope.buildb = $routeParams.buildb;
    var comparisonreq = $http.get('/comparison-info/' + $routeParams.builda + '/' +
                                  $routeParams.buildb);
    $scope.diff = comparisonreq.then(function(resp) {
        buildyRT.clear();
        var diff = resp.data;
        if(diff.compared.length === 0) {
            diff.empty = true;
        }
        return diff;
    }, function (error) {
        $scope.error = error;
    });
}

function BuildDetailCtrl($scope, $http, $routeParams, $rootScope, buildyRT) {
    $rootScope.title = "Build " + $routeParams.build;
    var buildinforeq = $http.get('/manifest-info/' + $routeParams.build);
    $scope.buildinfo = buildinforeq.then(function(resp) {
        buildyRT.clear();
        return resp.data;
    }, function(error) {
        $scope.error = error;
    });
}

function BuildListCtrl($scope, $http, $routeParams, $location, $rootScope, buildyRT) {
    $rootScope.title = "All Builds";
    var viewresults = $http.get('/allbuilds');
    $scope.builds = viewresults.then(function(resp) {
        return _(resp.data).map(function(row) {
            var build = row.doc.json.userdata;
            if(!build.fullversion) { return false; }
            build.filename = row.id.match(/\/([^\/]+)$/)[1];
            build.date = row.doc.json.modified;
            build.size = row.doc.json.length;
            build.ext = build.filename.match(/\.([^\.]+)$/)[1];
            var toy = build.filename.match(/_toy-([^\-]+)-/);
            if(toy) { build.toy = toy[1]; }
            if(!build.version) {
                build.version = build.fullversion.match(/^([^\-]+)-/)[1];
            }
            return build;
        }).filter().value();
    });


    function setupFilter(field) {
        $scope[field + 's'] = $scope.builds.then(function(builds) {
            return _(builds).pluck(field).uniq().value();
        });
    }
    _.each(["arch", "version", "license", "ext"], setupFilter);

    $scope.filtering = {};
    if($routeParams.filter) {
        $scope.filtering = JSON.parse($routeParams.filter);
    }
    $scope.toggleFilter = function(field, value) {
        if($scope.filtering[field]) {
            if(!_.contains($scope.filtering[field], value)) {
                $scope.filtering[field].push(value);
            } else {
                $scope.filtering[field] = _.without($scope.filtering[field], value);
                if(_.isEmpty($scope.filtering[field])) {
                    delete $scope.filtering[field];
                }
            }
        } else {
            $scope.filtering[field] = [value];
        }
        $location.search({filter: JSON.stringify($scope.filtering)});
    };

    $scope.included = function(field, value) {
        if(!value) { return $scope.filtering[field] !== undefined; }
        return _.contains($scope.filtering[field], value);
    };

    $scope.filtered = function(builds) {
        return _.filter(builds, function(build) {
            //if(_.isEmpty($scope.filtering)) { return true; }
            return _.every($scope.filtering, function(values, field) {
                return _.contains(values,build[field]);
            });
        });
    };


    $scope.compareA = function(build) {
        $scope.comparisonA = build;
    };
    $scope.cancelCompare = function() {
        delete $scope.comparisonA;
    };
    $scope.compareB = function(build) {
        $location.path('/compare/' + $scope.comparisonA.filename + '/' + build.filename);
    };

}
