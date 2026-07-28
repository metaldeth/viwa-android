/*******************************************************
This program was created by the
CodeWizardAVR V3.12 Advanced
Automatic Program Generator
© Copyright 1998-2014 Pavel Haiduc, HP InfoTech s.r.l.
http://www.hpinfotech.com

Project : Vita Flow Station
Version : 2.0
Date    : 25.03.2026
Author  : A
Company : Shaker Tech
Comments: 


Chip type               : ATmega2560
Program type            : Application
AVR Core Clock frequency: 14,745600 MHz
Memory model            : Small
External RAM size       : 0
Data Stack size         : 2048
*******************************************************/

#include <mega2560.h>
#include <delay.h>
#include <delay.h>
//=========================================================
#define long_rx   10  // command 0xXX, 10 byte data 
#define LED_M0        PORTG.0
#define LED_M1        PORTG.1

#define TRASH_WTR_SNS PIND.2
#define GLASS_SNS     PIND.3 

//--------------------------------
#define WTR_FILT_VALVE  PORTH.0 
#define WTR_COLD_VALVE  PORTH.1 
#define WTR_CARB_VALVE  PORTH.2 
#define WTR_PUMP_VALVE  PORTH.3 

// PORTH&=~(1<<PORTH0);  // set 0
// PORTH|=(1<<PORTH0);  // set 1  
//--------------------------------

#define PRST_PUMP1    PORTA.0
#define PRST_PUMP2    PORTA.1
#define PRST_PUMP3    PORTA.2
#define PRST_PUMP4    PORTA.3
#define PRST_PUMP5    PORTA.4
#define PRST_PUMP6    PORTA.5
#define PRST_PUMP7    PORTA.6
#define PRST_PUMP8    PORTA.7

#define PRST_PUMP9    PORTJ.2
#define PRST_PUMP10   PORTJ.3
#define PRST_PUMP11   PORTJ.4
#define PRST_PUMP12   PORTJ.5
//--------------------------------

//#define EOT          0xDA    // End of transmition
#define UART_ACK     0xFF    // correct acknowledgemnt
#define UART_NACK    0x00    // negative acknowledgemnt
#define T_Fault  30  // 30 sec  water absent fault
#define WATER_MAX_TIME  30 // 30 sec
#define WF_Fault  30  // 1/2 min  water absent fault
#define BLEEDING_TIME   30   // 30 sec 

//=========================================================
// Protein Machine state machine
enum {LOAD,AUTOMATIC,MIXER_CLEAN,ERRORS,BLOCKED}mode;  //  

// Prepare coctale state machine
enum {STOP,SELECT_BVRG,COOK_BVRG_NPT_MAIN,COOK_BVRG_ENDNPT_MAIN,COOK_BVRG_NPT_ADD,COOK_BVRG_ENDNPT_ADD,
COOK_BVRG_WATER_MAIN,COOK_NPT_END,COOK_BVRG_MERGE,COOK_BVRG_MERGE2,BVRG_OUT_DLAY}prep_bevrg_stage; 

// Clean mixers state machine
enum{STD_BY,BLEEDING_WSYSTEM,TEST_PERIFERAL,DISPENSER_TEST,WATERFILLING_TEST}clean_mix_state;  

// Declare your global variables here
//===================================================================================
//----------------------------------------------
union _water_cntdata
{
 unsigned char wtr_cnt_byte[2];
 unsigned int  wtr_cnt_word;
}water_cntdata;

//----------------------------------------------
//===================================================================================
// Declare your global variables here
 // Right setup 7 segment LED
 // =========================     0    1    2    3    4    5    6    7    8    9    10  pusto  ,   _    |    3-   E    t    F    n    b    r    A    U    c     g    *   П    C    L    ||   -    o    oup
flash unsigned char code_s[34]={0xC0,0xF9,0xA4,0xB0,0x99,0x92,0x82,0xD8,0x80,0x90,0xA2,0xFF,0x7F,0xF7,0xFE,0xB6,0x86,0x87,0x8E,0xAB,0x83,0xAF,0x88,0xC1,0xA7,0x98,0x9C,0xC8,0xC6,0xC7,0xC9,0xBF,0xA3,0x94};

 // Rotated 180* setup 7 segment LED
 // =========================     0    1    2    3    4    5    6    7    8    9    10  pusto  ,   _    |    3-   E    t    F    n    b    r    A    U    c     g    *   П    C    L    ||   -    o    oup
//flash unsigned char code_sr[34]={0xC0,0xCF,0x64,0x46,0x4B,0x52,0x50,0xC3,0x40,0x42,0x54,0xFF,0xBF,0xFE,0xEF,0x76,0x70,0x78,0x71,0x5D,0x58,0x7D,0x41,0xC8,0x7C,0x4A,0xE4,0xC1,0xF0,0xF8,0xC9,0xEF,0x14,0x63};
 
//Service port (Communications with PC)
unsigned char data_rx,dlina_dat,buff_rx[long_rx],rx_dat,addr_rx;
bit b_end_rx3;

eeprom unsigned char koef_w100;       // Koef_Water*100=koef_w100  (1.2*100=120)
eeprom float koeff_time;             // коэффициент пересчета времени проистекания воды.

float timer_prepare_bevarege_pf,data_water_tempf;
float  koef_w100fl, Koef_Water;      // Koef_Water*Water=pulses water counter ()

unsigned int water_counter_data,water_counter_predata,total_wtr_cnt;
unsigned char timer_second;
unsigned int  timer_prepare_bevarege_p,prepare_timer_im;
bit b_sec;

unsigned char Blocked_Status;

//-----------------------------------------------------------------------------
unsigned int water_counter_data,water_counter,water_counter_predata;
   
unsigned int data_water_temp,water_cnt_temp;
unsigned char Error; 

unsigned char timer_fault; 
unsigned char timer_fault_h; 

unsigned int time_water_fill;
unsigned int delta_time_cook;

unsigned int Time_Work_Dozators1,Time_Work_Dozators2,temp_Time_Work_Dozators1,temp_Time_Work_Dozators2;  
unsigned int timer_prepare_bevarege_p;

unsigned char current_container1;
unsigned int Data_Water1;
bit Powder_Syrup1;
unsigned char current_container2;
unsigned int Data_Water2;
bit Powder_Syrup2;
unsigned char Type_of_water;

bit b_selected_pbv,b_once_displ_e;
unsigned char Error,timer_prepare_m;
bit b_stop_water,b_stop_syrup;
unsigned char Peristaltic_PumpNum,Time_to_Moove;
unsigned int count_delay_clr;
bit b_PRP;
unsigned char cwater_touch,water_timer;
bit b_touch_knob;
unsigned char settings_cmam;
unsigned char Dispenser_num,Temp_DN;
unsigned int Dispenser_wtime;
unsigned char Timer_Klap_On_Clear;

bit b_error_pb;

char temp_0,temp_1; 
unsigned char temp_d;

unsigned int data_t0,data_t1;
unsigned int data_adc_t0,data_adc_t1;
unsigned int data_t0_f,data_t1_f;
unsigned char count_data_num0,count_data_num1;
bit b_ready_t0,b_ready_t1;

unsigned char LED_R,LED_G,LED_B;

//========= Programm Current Version ===================== 
//========================================================
flash unsigned char ver_year=26,ver_month=4,ver_day=6,sub_ver=1;
//========================================================
//=========================================================
// External Interrupt 1 service routine
interrupt [EXT_INT1] void ext_int1_isr(void)
{
// Place your code here
// Water counter
 water_counter_data++;
 water_counter_predata++;
 total_wtr_cnt++;

}
//=========================================================
#include "USART.c"
#include "Func.c"
//=========================================================
// Timer1 overflow interrupt service routine
interrupt [TIM1_OVF] void timer1_ovf_isr(void)
{
 // Reinitialize Timer1 value every 100ms
 TCNT1H=0xA600 >> 8;
 TCNT1L=0xA600 & 0xff;
 // Place your code here

 timer_prepare_bevarege_p++; 
 prepare_timer_im++;
 timer_second++;
   if(timer_second>=10) // 1 sec interval
     {
      timer_second=0;
      PORTB.7^=1; // flashes dot pixel on 7 segmet LED
      b_sec=1;
     }

}

// Voltage Reference: AREF pin
#define ADC_VREF_TYPE ((0<<REFS1) | (0<<REFS0) | (0<<ADLAR))

// Read the AD conversion result
unsigned int read_adc(unsigned char adc_input)
{
ADMUX=(adc_input & 0x1f) | ADC_VREF_TYPE;
if (adc_input & 0x20) ADCSRB|=(1<<MUX5);
else ADCSRB&=~(1<<MUX5);
// Delay needed for the stabilization of the ADC input voltage
delay_us(10);
// Start the AD conversion
ADCSRA|=(1<<ADSC);
// Wait for the AD conversion to complete
while ((ADCSRA & (1<<ADIF))==0);
ADCSRA|=(1<<ADIF);
return ADCW;
}

void main(void)
{
// Declare your local variables here
//=========================================================
#include "lowInit.c"
//=========================================================
//============================================================================
Reset_PM_Message_toPC();
//------------------------------------------------------------------------
  koef_w100fl=koef_w100;      // char to float
  
   if((koef_w100fl==0)||(koef_w100fl>=300)) {koef_w100=180; koef_w100fl=koef_w100; Koef_Water=1.80;}    // default value koeff water 1.8
      else
        Koef_Water=(float)(koef_w100fl/100); 
                      
 //----------------------------------------------------------------------- 
 // For debug DELETE this stroke
  // koeff_time=0.1; 
  // Koef_Water=1.8; 
 //----------------------------------------------------------------------- 
  // Red led-> Load Mode or Error Mode
  LED_R=0x80;    
  LED_G=0x00;
  LED_B=0x00;
  Set_Color();
   
 PORTB=code_s[29]; // display L symbol on 7 segment system LED 
 water_counter_data=0;
 Peristalic_Pump_On_Off(0);   
  Klap_Data(1);  // water valve
   Water_Pump_Valve(1);  // Water pump On Klapan On 
    
   mode=LOAD; // LOAD mode
//==============================================================================

// Global enable interrupts
#asm("sei")

while (1)
      {
       // Place your code here  
       //========================================================================
           if(b_ready_t0==0)
              { 
               data_adc_t0=read_adc(0);
                count_data_num0++;                   // filter 64 data adc0         
                 data_t0+=data_adc_t0;
                 if(count_data_num0>=64)  
                   {
                    data_t0_f=(data_t0>>=6);    
                    count_data_num0=0;
                    b_ready_t0=1;
                   }
              }   
            
             if(b_ready_t1==0)
               { 
                data_adc_t1=read_adc(1);
                count_data_num1++;                   // filter 64 data   adc1       
                data_t1+=data_adc_t1;
                if(count_data_num1>=64)  
                  {
                   data_t1_f=(data_t1>>=6);    
                   count_data_num1=0;
                   b_ready_t1=1;
                  }
               } 
         
           //-------------- temperature sensor1 --------------------------------------- 
             // 1 temperature sensor  Rntc=10k 
             // look-up for temparature, convert to real temperature   
              if(b_ready_t0==1)
                {
                 for(temp_d=0;temp_d<120;temp_d++)
                    {
                     if(data_t0_f<temp_list[temp_d])
                       { 
                        temp_0=(char)(temp_d-23);      // t>=0  Lower Sensor
                        if(temp_0<=0) temp_0+=0xFF;    // t<0 temp_0=0;
                        b_ready_t0=0;
                        break;                                                                                                         
                       } 
                    }      
                }
           //-------------- temperature sensor2 ---------------------------------------  
             // 2 temperature sensor  Rntc=10k 
             // look-up for temparature, convert to real temperature 
              if(b_ready_t1==1)
               {
                for(temp_d=0;temp_d<120;temp_d++)
                   {
                    if(data_t1_f<temp_list[temp_d])
                      { 
                       temp_1=(char)(temp_d-23);       //t>=0  Higher Sensor
                       if(temp_1<=0) temp_1+=0xFF;    // t<0 temp_1=0; temp_1=0;
                       b_ready_t1=0;
                       break;
                      } 
                   }
               } 
           
      //========================================================================
      if(b_sec==1)
        {
          b_sec=0;
          #asm("wdr")  
           timer_prepare_m++;
            timer_fault++;
             water_timer++;
              count_delay_clr++;
        }     
      //========================================================================
      //========== RX  Data vcom port 3 - Main and Service port (to PC) ========
      if(rx_counter3!=0)
        {
          data_rx=getchar3();
           if(dlina_dat!=0)
             {
              if(--dlina_dat==0) {b_end_rx3=1;}
              buff_rx[addr_rx++]=data_rx;
             }
           
             else
                 {
                  if(rx_dat==0xFE)     // Header 0xFE
                    {
                      dlina_dat=data_rx;
                      if(dlina_dat > long_rx) {dlina_dat=0;}
                      addr_rx=0;
                      rx_dat=0;
                      LED_M0=1;  
                    }
                      else
                          {
                           rx_dat=data_rx;
                           //----------- Remote programming mode entering -------
                           if(rx_dat=='B')  P_Mashine_Reset();
                           //----------------------------------------------------
                          }

                 }

        }
      //---------- обработка приёма с VCOM3 (from PC) --------------------------
      if(b_end_rx3==1)
        {
         b_end_rx3=0;
         delay_ms(1);
           
         switch(buff_rx[0]) // номер команды
               {  
                 case 0x11:  // Command =0x11  PC INCOMMING COMMAND: Test for Working machine
                    
                          Test_Message();
                     
                   break;
                 //============================================================= 
                  case 0x50:  // Command =0x50  // PC INCOMMING COMMAND:  Proteine COCTALE !!!!
                       
                      //------------------------------------------------------ 
                       Powder_Syrup1=buff_rx[1];        // Powder=0  Syrup=1
                       current_container1=buff_rx[2];   // current container;   
                       Time_Work_Dozators1=buff_rx[3];  //  милисекунды*10
                       Data_Water1=buff_rx[4];          // милилитры/10
                       
                       Powder_Syrup2=buff_rx[5];        // Powder=0  Syrup=1
                       current_container2=buff_rx[6];   // current container;   
                       Time_Work_Dozators2=buff_rx[7];
                       Data_Water2=buff_rx[8];
                       
                       Type_of_water=buff_rx[9];        // tupe of water 0->water from bottle, 1->cold water, 2 ->cold gas water
                       
                       b_error_pb=0;  // reset flag 
                        
                      //------------- Parameters analize ----------------------------------- 
                        
                        //powder=0 syrup=1
                       if(Powder_Syrup1>1) b_error_pb=1;           //powder=0 syrup=1 
                       if(current_container1>14) b_error_pb=1;
                       if(Data_Water1>0x64)   b_error_pb=1;          // water 1000 ml  
                       if((Time_Work_Dozators1>0)&&(Data_Water1==0))  b_error_pb=1;   // No water
                       
                       //-------------------------------------------------------------------
                        //powder=0 syrup=1
                       if(Powder_Syrup2>1) b_error_pb=1;           //powder=0 syrup=1 
                       if(current_container2>14) b_error_pb=1;
                       if(Data_Water2>0x64)   b_error_pb=1;          // water 1000 ml
                       if((Time_Work_Dozators2>0)&&(Data_Water2==0))  b_error_pb=1;   // No water
                     //----------------------------------------------------------------------  
                     
                         if(b_error_pb==0)
                           {
                            Send_UART3_ACK();  // No Errors!
                           } 
                              
                         if(b_error_pb==1)  // Errors!
                           {
                             Error=18;
                             Error_Of_Prepare_Beverage(Error);
                           } 
                          
                          
                    break;
                    
                     case 0x52:  // Command =0x52  // PC KEYPAD INCOMMING COMMAND: MIXER_CLEAN

                             settings_cmam=buff_rx[1];
                             
                             Timer_Klap_On_Clear=buff_rx[2];   // время промывки миксера 
                             Dispenser_num=buff_rx[3];         // Dispenser number 1-14 
                             Dispenser_wtime=buff_rx[4];       // Dispenser working Time in msec (255-> 25,5 sec) 
                             Data_Water1=buff_rx[5];           // количество воды в мл (0x32->500ml, 0x64->1000 ml) 
                           
                           
                                 if(settings_cmam==6)  // go to service mode
                                   {
                                    PORTB=code_s[27]; // display П 
                                    clean_mix_state=STD_BY; // stand by mode   
                                    mode=MIXER_CLEAN; 
                                    P_Machine_Mode_Set(mode);  //Send to PC message
                                   }
                                   else
                                       if(settings_cmam==7)  // go to automatic mode
                                         {
                                          PORTB=code_s[22]; // display A 
                                          b_once_displ_e=0;
                                          clean_mix_state=STD_BY;   // Waiting mode  
                                          mode=AUTOMATIC; // Automatic mode  work 
                                          P_Machine_Mode_Set(mode);  //Send to PC message    
                                         }
                                          else
                                        
                                            if(settings_cmam==9)  // go to Test dispenser
                                              {
                                               Start_Of_Prepare_Beverage();   // Message to PC 
                                               if((Dispenser_num>=9)&&(Dispenser_num<=20))    // Syrup
                                                 {
                                                  Temp_DN=Dispenser_num-8;
                                                  Peristalic_Pump_On_Off(Temp_DN);
                                                  
                                                  Klap_Data(0);
                                                  Water_Pump_Valve(0);  // Water pump Off or  Valve Off
                                                 }
                                                 
                                                prepare_timer_im=0;
                                                clean_mix_state=DISPENSER_TEST;
                                                mode=MIXER_CLEAN;
                                              }
                                              else
                                             if(settings_cmam==10)  // go to Test Water fillings to glass
                                               { 
                                                 Start_Of_Prepare_Beverage();   // Message to PC 
                                                 data_water_temp=(unsigned int)(Data_Water1*10);   // Data_Water1=Ml/10 (100ml -> 10)
                                                 water_counter=(unsigned int)(data_water_temp*Koef_Water);  // CALCULATE WATER COUNTER VALUE (PULSES) 
                                                 water_counter_data=0;  // reset water counter 
                                                 timer_fault=0;
                                                 timer_prepare_bevarege_p=0;  // reset prepereing timer 
                                                 
                                                 Klap_Data(1);  // water valve pp
                                                 Water_Pump_Valve(1);   // Water pump on 
                                                 Peristalic_Pump_On_Off(0);   
                                                     
                                                 clean_mix_state=WATERFILLING_TEST;
                                                 mode=MIXER_CLEAN;
                                               }
                                            
                                            
                                                                      
                              Send_UART3_ACK(); 
                             
                           break;
                    
                      case 0x55:  //  Command =0x55,  go to prepare beverage
                                   
                                
                                    b_selected_pbv=1;  // Cooking mode 
                                  
                                    timer_fault=0;   // reset fault timer
                                   
                                    Send_UART3_ACK();  
                                    
                                    prep_bevrg_stage=SELECT_BVRG;
                               
                      break;
                   
                     case 0x69:   // Read current errors

                           Error_Of_Prepare_Beverage(Error);

                      break; 
                      
                      case 0x94:  // Command =0x94   Read Working Mode

                                 P_Machine_Mode_Set(mode);

                                // putchar3(EOT);   // symbol "/n"

                      break;
                                        
                      case 0x95:  // Command =0x95   Reset P Mashine
                                                  
                                 Send_UART3_ACK();
                                 
                                 P_Mashine_Reset();

                      break; 
                      
                   case 0x97:  // Command =0x97   Read program Version

                                 putchar3(0xD5);   // identifier
                                 putchar3(0x06);  //  6 byte of data
                                 putchar3(0x49);   // comand -> Read program version
                                 putchar3(ver_year);  //  byte of data
                                 putchar3(ver_month);  //  byte of data
                                 putchar3(ver_day);  //  byte of data
                                 putchar3(sub_ver);  //  byte of data
                                 putchar3(0);  //  byte of data

                                // putchar3(EOT);   // symbol "/n"


                     break; 
                     
                   case 0xB8:  // Reset water counters

                                 water_cnt_temp=0;
                                 total_wtr_cnt=0;
                                 Send_UART3_ACK();

                    break;

                     case 0xB9:  // Read water counters

                                water_cnt_temp=total_wtr_cnt;
                                water_cntdata.wtr_cnt_word=(unsigned int)(water_cnt_temp*Koef_Water);

                                putchar3(0xD5);   // identifier
                                putchar3(0x06);  //  6 byte of data
                                putchar3(0x74);   // comand -> to PC
                                putchar3(water_cntdata.wtr_cnt_byte[1]);
                                putchar3(water_cntdata.wtr_cnt_byte[0]);
                                putchar3(0);
                                putchar3(water_cntdata.wtr_cnt_byte[1]);
                                putchar3(water_cntdata.wtr_cnt_byte[0]);
                                //putchar3(EOT);   // symbol "/n"
                      break;
                            
                            //----------------------------------------------------------------------------       
                    case 0xBB:  // Command =0xBB  
                                 
                                   // For all types of water counter !
                                   koef_w100=buff_rx[1]; 
                                   
                                   koef_w100fl=koef_w100;      // char to float
  
                                   if((koef_w100fl==0)||(koef_w100fl>=300)) {koef_w100=180; koef_w100fl=koef_w100; Koef_Water=1.80;}    // default value koeff water 1.8
                                     else
                                      Koef_Water=(float)(koef_w100fl/100); 
  
                                 Send_UART3_ACK(); 
                   
                                 break;
                   
                            case 0xBC:  // Command =0xBC Read Water koeff
                                
                                                              
                                  // For all types of water counter ! 
                                  putchar3(0xD5);   // identifier
                                  putchar3(0x06);   //  6 byte of data
                                  putchar3(0x88);   // 0x88 comand -> to PC  
                                  putchar3(koef_w100);
                                  putchar3(koef_w100);
                                  putchar3(koef_w100);
                                  putchar3(koef_w100);
                                  putchar3(koef_w100);
                                 
                                  //putchar3(EOT);   // symbol "/n"
                                                                  
                            break; 
                            
                        //-----------------------------------------------------------------------------           
                            case 0xBE:  // Peristaltic RePump
                                   Peristaltic_PumpNum=buff_rx[1];
                                   Time_to_Moove=buff_rx[2];
                                   timer_prepare_bevarege_p=0; // every 100 ms
                                   
                                   Peristalic_Pump_On_Off(Peristaltic_PumpNum);   
                                   
                                   b_PRP=1;
                                  
                                   Send_UART3_ACK();
                         
                            break;
                            
                    case 0xD0:  // Command =0xD0  Waterfilling to glass by touch knob
                           
                           cwater_touch=buff_rx[1];  //  cwater_touch: 0-> off valves, 1-> on valves
                           Type_of_water=buff_rx[2];   // type of water 0->water from bottle, 1->cold water, 2 ->cold gas water  water valves
                           
                           if(cwater_touch==0)
                             { 
                              Klap_Data(0);  // water valve
                              Water_Pump_Valve(0);   // Water pump on 
                              b_touch_knob=0;
                             }
                           
                           if(cwater_touch==1)
                             { 
                               Water_Pump_Valve(1);   // Water pump on 
                              // Klap_Data(1);   //water from bottle only
                               
                               // type of water 0->water from bottle, 1->cold water, 2 ->cold gas water    water valves
                              if(Type_of_water==0)  Klap_Data(1);   // 0->water from bottle   
                              if(Type_of_water==1)  Klap_Data(2);   // 1->cold water,
                              if(Type_of_water==2)  Klap_Data(3);   // 2 ->cold gas water
                              
                              water_timer=0; 
                              b_touch_knob=1;
                             }
                           
                           Send_UART3_ACK();  
                               
                           
                           break;     
                          
                     case 0xD1:  // Command =0xD1  Themperature from 2 sensors
                     
                           Send_Themp_to_PC(temp_0,temp_1);
                         
                      break; 
                      
                       case 0xD2:  // Command =0xD2   RGB LED Backlight data
                            
                          // PWM Data for RGB strip 
                           LED_R=buff_rx[1];    
                           LED_G=buff_rx[2];
                           LED_B=buff_rx[3];
                           Set_Color();
                           Send_UART3_ACK(); 
                      
                       break; 
                       
                         
                      case 0xD3:  // Command =0xD3  Trash water sensor data to PS
                       
                       Trash_WS(TRASH_WTR_SNS);
                       
                       break;            
                    
                     default: break;
                       
               }
         
         LED_M0=0;         
        } 
      //========================================================================
      //  Peristaltic RePump
      if(b_PRP==1)
        { 
          if(timer_prepare_bevarege_p>Time_to_Moove)  // Off peristaltic pump
            {
             Peristalic_Pump_On_Off(0);  // Off peristaltic pump
             b_PRP=0; 
            }
        } 
      //========================================================================
      if(b_touch_knob==1)  //Only  Water to Glass
        {   
         if(water_timer>WATER_MAX_TIME)  // 30 sec
           {
            cwater_touch=0;
            Klap_Data(0);  // water valve
            Water_Pump_Valve(0);   // Water pump on 
            b_touch_knob=0;
           }
        }    
      //================ LOAD Mode ============================================= 
      if(mode==LOAD)  // Load mode
        {  
            // TIME_PUMP_START sec run pump (default value: 6 sec)
            if((timer_prepare_m>=5)&&(timer_prepare_m<6))
              {
               if(water_counter_data<20) // Error 1, No water or pump fault
                 {
                  Klap_Data(0);
                  Water_Pump_Valve(0);  // Water pump Off Klapan Off 
                  Peristalic_Pump_On_Off(0);  // Dispenser (peristaltic pump) Off  
              
                  Error=1;
                  mode=ERRORS;  // ERRORS
                  P_Machine_Mode_Set(mode);  //Send to PC message
                 }
              }
             
            if(timer_prepare_m==6)
              {
                timer_prepare_m=7;
                Error=0;  
                Error_Of_Prepare_Beverage(Error);   //Send to PC message
                Klap_Data(0);
                Water_Pump_Valve(0);  // Water pump Off Klapan Off
                Peristalic_Pump_On_Off(0);   // Dispenser (peristaltic pump) Off 
              
                // reset water counters  and go to AUTOMATIC mode
                water_counter_data=0;
                prep_bevrg_stage=STOP;   // Start position on prepare of beverage
                timer_fault_h=0;
                
                // Green Led -> No Fault
                LED_R=0x00;    
                LED_G=0x80;
                LED_B=0x00;
                Set_Color(); 
                
                LED_M0=0;
                LED_M1=0;
               
                Exit_Selector();
              }  
            
        } // LOAD mode
      //============== AUTOMATIC MODE ==========================================
      if(mode==AUTOMATIC)  // automatic mode,  work with payment devices
        {
         //==========================================================================
         //===== Prepare beverage procedure =========================================
         //==========================================================================
            if(b_selected_pbv==0)   // End of prepareing beverage
              {  
                // Waiting state, do nothing!!! 
                LED_M1=0;
              }      
            //------------------------------------------------
             if(b_selected_pbv==1)  // Coctale Cooking mode
               {
                 #include "Prepare_Beverage.c"; 
                 //================================================================ 
                 
                   //--------------------------------------------------------------- 
                   /*
                    if(b_water_analize==1)
                      {                        
                        if(timer_prepare_bevarege_p==TP_BVRG) b_water_monitor=0;  //  5 sec
                          if(timer_prepare_bevarege_p>TP_BVRGN)  //  >5.3 sec
                            {
                             // every 2 second test water counter
                             if(b_water_monitor==1) 
                               { 
                                b_water_monitor=0;
                                if(water_counter_predata<NPMC)  // <2 pulses ->No water
                                  {
                                   counter_nowater++;
                                   if(counter_nowater>=2)
                                     {
                                      Error=1;           //  Fault mode  
                                      mode=ERRORS;
                                     } 
                                  }
                                if(water_counter_predata>=NPMC)  // >=2 pulses ->No water
                                  { 
                                   counter_nowater=0;
                                   water_counter_predata=0;
                                  }   
                               }   
                            }
                      }  
                     */  
                     if(timer_fault>T_Fault)  // Fault mode
                       {
                        Error=1;
                        mode=ERRORS;
                       } // Fault mode
                   //================================================================
               }  
          
        } // AUTOMATIC mode
      //=================== MIXER CLEAN  MODE ==================================
      if(mode==MIXER_CLEAN)  // Cleaning mixers
        { 
           //----------- Bleeding water system ---------------------------        
             if(clean_mix_state==BLEEDING_WSYSTEM) // Bleeding water system
               {
                if(count_delay_clr>=BLEEDING_TIME)
                  {
                   Klap_Data(0);
                   Water_Pump_Valve(0);  // Water pump Off Klapan Off
                   clean_mix_state=STD_BY;
                   Error=0;
                   mode=MIXER_CLEAN;
                  }
              
               }    
            //---------------- Test dispenser ---------------------------
           
             if(clean_mix_state==DISPENSER_TEST) //Test product dispenser
               {
                 if(prepare_timer_im>=Dispenser_wtime)   // Dispenser working Time in msec (255-> 25,5 sec)
                   {
                     Klap_Data(0);
                     Water_Pump_Valve(0);  // Water pump Off Klapan Off  
                     Peristalic_Pump_On_Off(0);   // Dispenser (peristaltic pump) Off
                     End_Of_Prepare_Beverage();   // Message to PC  
                     clean_mix_state=STD_BY;
                   }
               } 
             
             if(clean_mix_state==WATERFILLING_TEST) //only water filling to glas test
               {
                if(water_counter_data>=water_counter)
                  { 
                   timer_prepare_bevarege_pf=timer_prepare_bevarege_p;
                   data_water_tempf=data_water_temp;
                   // calculated koefficient for max time prepareing
                   koeff_time=(float)(timer_prepare_bevarege_pf/data_water_tempf);    //data_water_temp in ml;    timer_prepare_bevarege_p in msec 
                  
                   Water_Pump_Valve(0);  // Water pump Off Klapan Off 
                   Peristalic_Pump_On_Off(0);  // Dispenser (peristaltic pump) Off 
                   timer_fault_h=0; 
                   End_Of_Prepare_Beverage();   // Message to PC  
                   clean_mix_state=STD_BY;  
                  } 
                //================================================================  
                 if(timer_fault>WF_Fault)  //Water Filling Fault mode
                   {
                    Error=1;
                    mode=ERRORS;
                   } // Fault mode
               }
              
          //===================== Test periferial =================================                       
          /*  
            if(clean_mix_state==TEST_PERIFERAL) // Test periferial 
               { 
                if(b_once_tst_tx==1)
                  {
                    b_once_tst_tx=0;
             
                   if(counter_t_periferals==0)
                     {
                      Data_Port_Out_CH1(0x00,0x00);  //All Off 
                      Data_Port_Out_CH2(0x00,0x00);  // All Off   
                     }
             
                   if(counter_t_periferals==1) 
                     {
                      data_testp=2;
                      Data_Port_Out_CH1(0x00,0x00);  //All Off 
                      Data_Port_Out_CH2(0x00,0x00);  //All Off      
                     }
              
                   if((counter_t_periferals>=3)&&(counter_t_periferals<=8))
                     {
                      data_testp<<=1;
                      Data_Port_Out_CH1(0x00,0x00);  //All Off 
                      Data_Port_Out_CH2(0x00,data_testp);  //   
                     }
           
                    //-------------------------------------------------
                   if(counter_t_periferals==9) 
                     {
                      data_testp=1;
                      Data_Port_Out_CH1(0x00,data_testp);  //  
                      Data_Port_Out_CH2(0x00,0x00);  //All Off   
                     } 
             
                   if((counter_t_periferals>=10)&&(counter_t_periferals<=16))
                     {
                      data_testp<<=1;
                      Data_Port_Out_CH1(0x00,data_testp);  //  
                      Data_Port_Out_CH2(0x00,0x00);  //All Off   
                     }
                   //----------------------------------------------   
                   if(counter_t_periferals==17) 
                     {
                      data_testp=1;
                      Data_Port_Out_CH1(data_testp,0x00);  //  
                      Data_Port_Out_CH2(0x00,0x00);  //All Off   
                     }       
           
                   if((counter_t_periferals>=18)&&(counter_t_periferals<=24))
                     {
                      data_testp<<=1;
                      Data_Port_Out_CH1(data_testp,0x00);  //  
                     Data_Port_Out_CH2(0x00,0x00);  //All Off   
                     }
           
                   //-------------------------------------------------   
                   if(counter_t_periferals==25) 
                     {
                      data_testp=1; 
                      Data_Port_Out_CH1(0x00,0x00);  //All Off 
                      Data_Port_Out_CH2(data_testp,0x00);  //   
                     } 
            
                   if((counter_t_periferals>=26)&&(counter_t_periferals<=32))
                     {
                      data_testp<<=1;
                      Data_Port_Out_CH1(0x00,0x00);  //All Off 
                      Data_Port_Out_CH2(data_testp,0x00);  //
                     } 
               
                   if(counter_t_periferals>=33)
                     {
                      clean_mix_state=STD_BY;
                      b_once_st_tx=0;
                      Data_Port_Out_CH1(0x00,0x00);  // ALL off
                      Data_Port_Out_CH2(mod_valve_data,0x08|mod_heater_data);   //LED GREEN  // go to Mixer Clean mode
                     }
                   //---------------------------------------------------
                  }   
               }
          */
          //----------- automatic clean mixers Hot water---------------------------
           
        } //MIXER_CLEAN mode
      //=================== ERRORS ============================================= 
       if(mode==ERRORS) // ERRORS
        {
          // Error List:
          // Error 0   -> No error
          // Error 1   -> Water absent or Pump fault
          // Error 2   -> Beverage1 absent or Pump fault
          // Error 3   -> Beverage2 absent or Pump fault
          // Error 4   -> Beverage3 absent or Pump fault
          // Error 5   -> Glass out fault
          // Error 6   -> Billvalidator error
          // Error 7   -> Cashless device error
          // Error 8   -> Coin Changer error
          // Error 9   -> Manipulator error
          // Error 10  -> Driver Board  Fault
          // Error 18  -> Parameters of prepare beverage Fault  
          // Error 19  -> Bucket is Full 
          // Error 20  -> Snack Product absent (bloked product position) 
          // Error 21  -> Timeout exit from Inserted External Glass (Touch2) 
          // Error 30  -> no phisical communications MDBus (no contact in connector) - abcent
          // Error 34  -> Level MDB incorrect CD1
          
          // Max number hardware errror = 50!!! 
       
         //====================================================================
          
         if(b_once_displ_e==0)
           {
            LED_M0=0;
            LED_M1=0;
            Water_Pump_Valve(0);   // Water pump off   
             Klap_Data(0);  // valve for peristaltic pump 
              Peristalic_Pump_On_Off(0);  // Dispenser (peristaltic pump) Off
               prep_bevrg_stage=STOP;
                Error_Of_Prepare_Beverage(Error);   //Send to PC message
                  PORTB=code_s[Error]; // display number of Error
                  
                   // Red led-> Load Mode or Error Mode
                   LED_R=0x80;    
                   LED_G=0x00;
                   LED_B=0x00;
                   Set_Color(); 
                                    
                   b_selected_pbv=0;
                   b_once_displ_e=1;
                   
           } // b_once_displ_e   
            
        }// ERRORS
      //========================================================================
      } // while
} // main


