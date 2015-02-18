function map(doc, meta)
    {
    var extremap = {
        exe: 'Windows',
        zip: 'Mac OS X'
    };
    if(meta.type == 'json' && doc.userdata && doc.userdata.product && doc.userdata.product == 'couchbase-server')
        {
        var build  = doc.userdata;
        build.filename = meta.id.match(/\/([^\/]+)$/)[1];
        build.date = doc.modified;
        build.size = doc.length;
        build.ext  = build.filename.match(/\.([^\.]+)$/)[1];
        build.os   = extremap[build.ext];

       if(build.ext == 'rpm')
           {
           if (build.filename.indexOf("centos6") > 0)     { build.os = "CentOS 6"; }
           else                                           { build.os = "CentOS 5"; }
           }
       if(build.ext == 'deb')
           {
           if (build.filename.indexOf("ubuntu_1204") > 0) { build.os = "Ubuntu 12.04"; }
           else                                           { build.os = "Ubuntu 10.04"; }
           }
       var toy = build.filename.match(/_toy-([^\-]+)-/);
       if (toy) { build.toy = toy[1]; }
       if (!build.fullversion)
           {
           build.fullversion = build.filename.match(/([^\-_]+-[^\-]+)-[^\-]+\.[^.]+$/);
           if(build.fullversion)
               {
               build.fullversion = build.fullversion[1];
               }
           }
       if (!build.version && build.fullversion)
           {
           build.version = build.fullversion.match(/^([^\-]+)-/);
           if(build.version)
               {
               build.version = build.version[1];
               }
           }
                //Should maybe have that thing have a type indicator?
       if (build.fullversion)
           {
           emit(["version", build.fullversion], build);
           emit(["date", doc.modified], build);
           }
       else
           {
           emit(["problem", doc.modified], build);
           }
       }
    }
