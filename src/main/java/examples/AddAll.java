package examples;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.WeakHashMap;

public class AddAll {
    public static void main(String[] args) {
        for (int j = 0; j < 1; j++) {
            Map m = new WeakHashMap(100000);
            for (int i = 0; i < 100000; i++)
                m.put(new Object(), Boolean.TRUE);
            ArrayList arrayList = new ArrayList();
            boolean result = arrayList.addAll(m.keySet());
            assert result;
            assert arrayList.size() == m.size();
            assert arrayList.containsAll(m.keySet());
        }

        for (int j = 0; j < 1; j++) {
            Map m = new WeakHashMap(100000);
            for (int i = 0; i < 100000; i++)
                m.put(new Object(), Boolean.TRUE);
            LinkedList linkedList = new LinkedList();
            boolean result = linkedList.addAll(m.keySet());
            assert result;
            assert linkedList.size() == m.size();
            assert linkedList.containsAll(m.keySet());
        }

        for (int j = 0; j < 1; j++) {
            Map m = new WeakHashMap(100000);
            for (int i = 0; i < 100000; i++)
                m.put(new Object(), Boolean.TRUE);
            Vector vector = new Vector();
            boolean result = vector.addAll(m.keySet());
            assert result;
            assert vector.size() == m.size();
            assert vector.containsAll(m.keySet());
        }

        for (int j = 0; j < 1; j++) {
            Map m = new WeakHashMap(100000);
            for (int i = 0; i < 100000; i++)
                m.put(new Object(), Boolean.TRUE);
            List list = new ArrayList();
            list.add("inka"); list.add("dinka"); list.add("doo");
            boolean result = list.addAll(1, m.keySet());
            assert result;
            assert list.size() == 3 + m.size();
            assert list.get(0).equals("inka");
            assert list.subList(1, 1 + m.size()).containsAll(m.keySet());
            assert list.get(1 + m.size()).equals("dinka");
            assert list.get(2 + m.size()).equals("doo");
        }

        for (int j = 0; j < 1; j++) {
            Map m = new WeakHashMap(100000);
            for (int i = 0; i < 100000; i++)
                m.put(new Object(), Boolean.TRUE);
            List list = new LinkedList();
            list.add("inka"); list.add("dinka"); list.add("doo");
            boolean result = list.addAll(1, m.keySet());
            assert result;
            assert list.size() == 3 + m.size();
            assert list.get(0).equals("inka");
            assert list.subList(1, 1 + m.size()).containsAll(m.keySet());
            assert list.get(1 + m.size()).equals("dinka");
            assert list.get(2 + m.size()).equals("doo");
        }

        for (int j = 0; j < 1; j++) {
            Map m = new WeakHashMap(100000);
            for (int i = 0; i < 100000; i++)
                m.put(new Object(), Boolean.TRUE);
            List list = new ArrayList();
            list.add("inka"); list.add("dinka"); list.add("doo");
            boolean result = list.addAll(1, m.keySet());
            assert result;
            assert list.size() == 3 + m.size();
            assert list.get(0).equals("inka");
            assert list.subList(1, 1 + m.size()).containsAll(m.keySet());
            assert list.get(1 + m.size()).equals("dinka");
            assert list.get(2 + m.size()).equals("doo");
        }
    }
}
