package com.flixtown.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.leanback.widget.HorizontalGridView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ActorActivity extends Activity {
    private LinearLayout content;
    private TextView message;
    @Override public void onCreate(Bundle state){super.onCreate(state);Images.init(this);
        LinearLayout shell=Ui.column(this);shell.setBackgroundColor(Ui.BG);Ui.pad(shell,this,65,36,50,20);setContentView(shell);
        String name=getIntent().getStringExtra("actor_name");if(name==null)name="Actor";
        shell.addView(Ui.heading(this,name,31));
        message=Ui.text(this,"Finding titles in Flix Town…",17);Ui.pad(message,this,0,14,0,20);shell.addView(message);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setClipChildren(false);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=Ui.column(this);scroll.addView(content);
        int id=getIntent().getIntExtra("actor_id",0);String actorName=name;
        Api.IO.execute(()->{try{
            String endpoint=BuildConfig.PANEL_URL+"actor.php?"+(id>0?"id="+id:"name="+Api.enc(actorName));
            JSONObject response=Api.get(endpoint);JSONArray titles=response.optJSONArray("titles");
            Set<String> credits=new HashSet<>();if(titles!=null)for(int i=0;i<titles.length();i++){
                JSONObject title=titles.optJSONObject(i);if(title!=null)credits.add(title.optString("kind")+":"+normalize(title.optString("title")));
            }
            List<Catalog.Item> movies=matched("movies","movie",credits),series=matched("series","series",credits);
            runOnUiThread(()->{message.setText(movies.isEmpty()&&series.isEmpty()?"No matching titles in your catalog yet":"Titles available in Flix Town");
                addRow("Movies",movies);addRow("TV Shows",series);});
        }catch(Exception e){runOnUiThread(()->message.setText("Actor titles are unavailable right now."));}});
    }
    private static String normalize(String text){return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private List<Catalog.Item> matched(String cache,String kind,Set<String> credits){
        List<Catalog.Item> result=new ArrayList<>();for(Catalog.Item item:Catalog.parse(Api.cached(this,cache),kind))
            if(credits.contains(kind+":"+normalize(item.title)))result.add(item);return result;
    }
    private void addRow(String heading,List<Catalog.Item> items){if(items.isEmpty())return;
        TextView label=Ui.heading(this,heading,22);Ui.pad(label,this,0,16,0,10);content.addView(label);
        HorizontalGridView grid=new HorizontalGridView(this);grid.setNumRows(1);grid.setItemAnimator(null);
        grid.setClipToPadding(false);grid.setClipChildren(false);grid.setPadding(Ui.dp(this,24),Ui.dp(this,8),Ui.dp(this,24),Ui.dp(this,8));grid.setHorizontalSpacing(Ui.dp(this,16));
        grid.setAdapter(new PosterAdapter(this,items,item->{}));
        content.addView(grid,new LinearLayout.LayoutParams(-1,PosterAdapter.rowListHeight(this)));
    }
}
