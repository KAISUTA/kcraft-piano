package jp.kcraft.piano;

/** Visible models and ray intersection share this local coordinate frame. +Z is front. */
final class KeyboardGeometry {
    static final double WHITE_WIDTH=.05, WHITE_FLOOR=1.21, BLACK_FLOOR=1.245;
    final double[] x=new double[88]; final boolean[] black=new boolean[88];
    KeyboardGeometry(){int whites=0;for(int i=0;i<88;i++){int pc=(i+21)%12;black[i]=pc==1||pc==3||pc==6||pc==8||pc==10;x[i]=-1.3+(whites+(black[i]?0:.5))*WHITE_WIDTH;if(!black[i])whites++;}}
    double floor(int i){return black[i]?BLACK_FLOOR:WHITE_FLOOR;}
    double centerZ(int i){return black[i]?.20:.24;}
    double depthScale(int i){return black[i]?.13:.20;}
    record Hit(int note,double distance){}
    Hit ray(double ox,double oy,double oz,double dx,double dy,double dz,double reach){
        int note=-1;double nearest=reach;
        for(int i=0;i<88;i++){
            double half=black[i]?.0125:.025;
            double minZ=centerZ(i)-.4375*depthScale(i),maxZ=centerZ(i)+.5*depthScale(i);
            double distance=box(ox,oy,oz,dx,dy,dz,x[i]-half-.001,floor(i)-.002,minZ-.002,x[i]+half+.001,floor(i)+.052,maxZ+.002);
            if(distance>=0&&distance<nearest){nearest=distance;note=i+21;}
        }
        return note<0?null:new Hit(note,nearest);
    }
    static double box(double ox,double oy,double oz,double dx,double dy,double dz,double x0,double y0,double z0,double x1,double y1,double z1){
        double enter=0,exit=Double.POSITIVE_INFINITY;
        double[] o={ox,oy,oz},d={dx,dy,dz},min={x0,y0,z0},max={x1,y1,z1};
        for(int axis=0;axis<3;axis++){
            if(Math.abs(d[axis])<1e-10){if(o[axis]<min[axis]||o[axis]>max[axis])return -1;continue;}
            double a=(min[axis]-o[axis])/d[axis],b=(max[axis]-o[axis])/d[axis];if(a>b){double t=a;a=b;b=t;}
            enter=Math.max(enter,a);exit=Math.min(exit,b);if(enter>exit)return -1;
        }
        return exit<0?-1:enter;
    }
}
