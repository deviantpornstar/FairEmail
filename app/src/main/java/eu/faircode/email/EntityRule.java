package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2019 by Marcel Bokhorst (M66B)
*/

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import static androidx.room.ForeignKey.CASCADE;

@Entity(
        tableName = EntityRule.TABLE_NAME,
        foreignKeys = {
                @ForeignKey(childColumns = "folder", entity = EntityFolder.class, parentColumns = "id", onDelete = CASCADE),
        },
        indices = {
                @Index(value = {"folder"}),
                @Index(value = {"order"})
        }
)
public class EntityRule {
    static final String TABLE_NAME = "rule";

    @PrimaryKey(autoGenerate = true)
    public Long id;
    @NonNull
    public Long folder;
    @NonNull
    public String name;
    @NonNull
    public int order;
    @NonNull
    public boolean enabled;
    @NonNull
    public boolean stop;
    @NonNull
    public String condition;
    @NonNull
    public String action;

    static final int TYPE_SEEN = 1;
    static final int TYPE_UNSEEN = 2;
    static final int TYPE_MOVE = 3;

    boolean matches(Context context, EntityMessage message) throws IOException {
        try {
            JSONObject jcondition = new JSONObject(condition);

            JSONObject jsender = jcondition.optJSONObject("sender");
            if (jsender != null) {
                String sender = jsender.getString("value");
                boolean regex = jsender.getBoolean("regex");

                boolean matches = false;
                if (message.from != null) {
                    for (Address from : message.from) {
                        InternetAddress ia = (InternetAddress) from;
                        String personal = ia.getPersonal();
                        String formatted = ((personal == null ? "" : personal + " ") + "<" + ia.getAddress() + ">");
                        if (matches(sender, formatted, regex)) {
                            matches = true;
                            break;
                        }
                    }
                }
                if (!matches)
                    return false;
            }

            JSONObject jsubject = jcondition.optJSONObject("subject");
            if (jsubject != null) {
                String subject = jsubject.getString("value");
                boolean regex = jsubject.getBoolean("regex");

                if (!matches(subject, message.subject, regex))
                    return false;
            }

            // Safeguard
            if (jsender == null && jsubject == null)
                return false;
        } catch (JSONException ex) {
            Log.e(ex);
            return false;
        }

        return true;
    }

    private boolean matches(String needle, String haystack, boolean regex) {
        Log.i("Matches needle=" + needle + " haystack=" + haystack + " regex=" + regex);

        if (needle == null || haystack == null)
            return false;

        if (regex) {
            Pattern pattern = Pattern.compile(needle);
            return pattern.matcher(haystack).matches();
        } else
            return haystack.contains(needle);
    }

    void execute(Context context, DB db, EntityMessage message) {
        try {
            JSONObject jargs = new JSONObject(action);
            int type = jargs.getInt("type");
            Log.i("Executing rule=" + type + " message=" + message.id);

            switch (type) {
                case TYPE_SEEN:
                    onActionSeen(context, db, message, true);
                    break;
                case TYPE_UNSEEN:
                    onActionSeen(context, db, message, false);
                    break;
                case TYPE_MOVE:
                    onActionMove(context, db, message, jargs);
                    break;
            }
        } catch (JSONException ex) {
            Log.e(ex);
        }
    }

    private void onActionSeen(Context context, DB db, EntityMessage message, boolean seen) {
        EntityOperation.queue(context, db, message, EntityOperation.SEEN, seen);
        message.seen = seen;
    }

    private void onActionMove(Context context, DB db, EntityMessage message, JSONObject jargs) throws JSONException {
        long target = jargs.getLong("target");
        EntityOperation.queue(context, db, message, EntityOperation.MOVE, target, false);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof EntityRule) {
            EntityRule other = (EntityRule) obj;
            return this.folder.equals(other.folder) &&
                    this.name.equals(other.name) &&
                    this.order == other.order &&
                    this.enabled == other.enabled &&
                    this.stop == other.stop &&
                    this.condition.equals(other.condition) &&
                    this.action.equals(other.action);
        } else
            return false;
    }
}
