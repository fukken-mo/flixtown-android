<?php
require __DIR__.'/../includes/account_sync.php';require __DIR__.'/../includes/profiles.php';
$n=0;function check($ok,$why){global $n;if(!$ok)throw new RuntimeException($why);$n++;}
$base=['id'=>'default','name'=>'موافـق','avatar'=>'avatar_28','kids'=>false];
check(fxp_clean($base)['name']==='موافـق','Arabic name');check(fxp_clean(array_merge($base,['name'=>' Movie 🎬 ']))['name']==='Movie 🎬','emoji and trimming');
foreach(['','   ',str_repeat('a',25),"line\nfeed","bad\u{202e}name"] as $name){try{fxp_clean(array_merge($base,['name'=>$name]));throw new RuntimeException('accepted bad name');}catch(FlixSyncError $e){check($e->status===400,'name refused');}}
foreach(['avatar_00','avatar_29','../x','https://image'] as $a){try{fxp_clean(array_merge($base,['avatar'=>$a]));throw new RuntimeException('accepted bad avatar');}catch(FlixSyncError $e){check($e->status===400,'avatar refused');}}
check(fxp_id('12345678-1234-4234-8234-123456789012'),'UUID valid');check(!fxp_id('../bad'),'path refused');check(!fxp_id(null),'null refused');
echo "Profile validation: $n passed\n";
